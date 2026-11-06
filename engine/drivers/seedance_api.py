"""
Seedance (video) / Seedream (ảnh) qua BytePlus ModelArk, API chính thức có API key.

Video: POST {base}/api/v3/contents/generations/tasks rồi hỏi lại GET .../tasks/{id} tới khi xong, tải content.video_url.
Ảnh:   POST {base}/api/v3/images/generations (Seedream), nhận ảnh base64.

Ảnh tham chiếu gửi dạng base64 (data:image/...;base64,...) với role "reference_image", đúng thứ tự cột Reference IDs.
Prompt được thêm một dòng đầu cho model biết [Image 1] là ID nào, vì prompt Omni gọi ảnh bằng ID chứ không bằng số thứ tự.

Model phải được bật trong ModelArk console (không thì API báo ModelNotOpen). Tên model đặt ở tài khoản, trống thì dùng mặc định
dưới đây; địa chỉ API đổi bằng biến môi trường AFM_ARK_BASE_URL (mặc định vùng ap-southeast).
"""
import base64
import mimetypes
import os
import re
import time
from pathlib import Path

import requests

from .errors import AuthRequired, Cancelled, ConfigError, GenerationResult, QuotaExceeded, Rejected, TransientError

BASE_URL = os.environ.get("AFM_ARK_BASE_URL", "https://ark.ap-southeast.bytepluses.com").rstrip("/")
DEFAULT_VIDEO_MODEL = os.environ.get("AFM_SEEDANCE_MODEL", "dreamina-seedance-2-0-mini-260615")
DEFAULT_IMAGE_MODEL = os.environ.get("AFM_SEEDREAM_MODEL", "seedream-5-0-lite-260128")
MAX_REFS = int(os.environ.get("AFM_SEEDANCE_MAX_REFS", "9"))
MAX_REF_BYTES = 10 * 1024 * 1024
# Model có tạo tiếng (Playground: "Audio"): Seedance 1.5 / 2.x. Model cũ (1.0) gửi tham số này sẽ bị từ chối nên không gửi.
_AUDIO_MODEL_RE = re.compile(r"seedance-(1-5|2-)", re.I)
GENERATE_AUDIO = os.environ.get("AFM_SEEDANCE_AUDIO", "1") != "0"
POLL_SECONDS = 10

_DURATION_RE = re.compile(r"duration\s*:?\s*(\d{1,2})\s*(?:s\b|sec|giây)", re.I)


def _ref_id(path: str) -> str:
    """Tên ảnh như prompt Omni gọi: file CHAR-CLARA-1.png → @CHAR-CLARA-1."""
    return "@" + Path(path).stem.lstrip("@")


def _data_url(path: str) -> str:
    p = Path(path)
    if not p.is_file():
        raise Rejected(f"Không thấy file ảnh tham chiếu: {p}")
    data = p.read_bytes()
    if len(data) > MAX_REF_BYTES:
        raise Rejected(f"Ảnh tham chiếu {p.name} nặng {len(data) // (1024 * 1024)}MB, quá 10MB: hãy giảm dung lượng ảnh")
    mime = mimetypes.guess_type(p.name)[0] or "image/png"
    return f"data:{mime};base64,{base64.b64encode(data).decode('ascii')}"


ASSET_FILE = "modelark-assets.txt"
_CONTENT_IDX_RE = re.compile(r"content\[(\d+)\]")


def _asset_map(refs: list[str]) -> dict[str, str]:
    """
    Ảnh đã đăng ký trong kho tài sản ModelArk (My assets / Digital characters): file modelark-assets.txt nằm cạnh ảnh
    tham chiếu (Folder Ảnh), mỗi dòng "CHAR-CLARA-1 = <asset id>". Ảnh có trong danh sách được gửi bằng asset://<id>
    thay cho nội dung file. Dòng bắt đầu bằng # là ghi chú.
    """
    out: dict[str, str] = {}
    for d in dict.fromkeys(Path(r).parent for r in refs):
        f = d / ASSET_FILE
        if not f.is_file():
            continue
        for line in f.read_text(encoding="utf-8-sig").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            name, asset = (x.strip() for x in line.split("=", 1))
            asset = asset.removeprefix("asset://")
            if name and asset:
                out.setdefault(name.lstrip("@").upper(), asset)
    return out


def _ref_url(path: str, assets: dict[str, str]) -> str:
    asset = assets.get(Path(path).stem.lstrip("@").upper())
    return f"asset://{asset}" if asset else _data_url(path)


def _with_ref_legend(prompt: str, refs: list[str]) -> str:
    """'[Image 1] = @CHAR-CLARA-1, …': model biết ảnh gửi kèm thứ mấy là tên nào được gọi trong prompt."""
    if not refs:
        return prompt
    legend = ", ".join(f"[Image {i}] = {_ref_id(r)}" for i, r in enumerate(refs, 1))
    return f"Reference images: {legend}.\n\n{prompt}"


def _duration(job: dict) -> int | None:
    if job.get("durationSeconds"):
        return int(job["durationSeconds"])
    m = _DURATION_RE.search(job.get("prompt") or "")
    return int(m.group(1)) if m else None


class SeedanceDriver:
    def __init__(self, http=None):
        self.http = http or requests.Session()

    # ---------- chung ----------
    @staticmethod
    def _key(acc: dict) -> str:
        key = (acc or {}).get("apiKey")
        if not key:
            raise ConfigError(f"Tài khoản {(acc or {}).get('email')} chưa nhập API key ModelArk")
        return key

    def _call(self, method: str, path: str, key: str, body: dict | None = None, timeout: float = 60):
        try:
            r = self.http.request(method, f"{BASE_URL}{path}", json=body, timeout=timeout,
                                  headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
        except requests.RequestException as e:
            raise TransientError(f"Không gọi được ModelArk: {e}") from e
        if r.status_code < 400:
            return r.json() if r.content else {}
        try:
            err = (r.json() or {}).get("error") or {}
        except ValueError:
            err = {}
        code, msg = str(err.get("code") or ""), err.get("message") or r.text[:500]
        text = f"ModelArk {r.status_code} {code}: {msg}".strip()
        if "PrivacyInformation" in code or "real person" in msg.lower():
            raise Rejected(self._privacy_hint(msg, body) + f" ({text})")
        if r.status_code in (401, 403) and "ModelNotOpen" not in code:
            raise AuthRequired(f"API key sai hoặc hết hạn ({text})")
        if r.status_code == 429 or "Quota" in code or "RateLimit" in code:
            raise QuotaExceeded(text)
        if "ModelNotOpen" in code or "NotFound" in code:
            raise ConfigError(f"Model chưa bật hoặc sai tên model: vào ModelArk console bật model rồi chạy lại ({text})")
        if r.status_code >= 500:
            raise TransientError(text)
        raise Rejected(text)

    @staticmethod
    def _privacy_hint(msg: str, body: dict | None) -> str:
        """content[i] trong lỗi → tên ảnh (content[0] là prompt, content[i] là ảnh thứ i)."""
        names = []
        items = (body or {}).get("content") or []
        legend = items[0].get("text", "") if items else ""
        for i in dict.fromkeys(int(x) for x in _CONTENT_IDX_RE.findall(msg)):
            m = re.search(rf"\[Image {i}\] = (@[^,.\s]+)", legend)
            names.append(m.group(1) if m else f"ảnh thứ {i}")
        which = ", ".join(names) or "ảnh tham chiếu"
        return (f"Seedance từ chối {which}: ảnh trông như người thật. ModelArk không nhận ảnh mặt người thật gửi trực tiếp. "
                f"Cách dùng được: đăng ký nhân vật vào kho tài sản ModelArk (My assets / Digital characters, cần người đó đồng ý) "
                f"rồi ghi mã asset vào file {ASSET_FILE} trong Folder Ảnh, hoặc dùng ảnh nhân vật được tạo ngay trên ModelArk")

    def generate_foreground(self, job, should_stop):
        raise Rejected("Seedance (API) không có chế độ FOREGROUND: đổi phim sang chạy nền")

    def generate(self, job: dict, should_stop) -> GenerationResult:
        staging = Path(job["stagingDir"])
        staging.mkdir(parents=True, exist_ok=True)
        key = self._key(job.get("account"))
        if job["kind"] == "IMAGE":
            return self._image(job, key, staging)
        return self._video(job, key, staging, should_stop)

    # ---------- ảnh: Seedream ----------
    def _image(self, job: dict, key: str, staging: Path) -> GenerationResult:
        refs = job.get("referenceImages") or []
        body = {
            "model": (job["account"].get("imageModel") or DEFAULT_IMAGE_MODEL),
            "prompt": _with_ref_legend(job["prompt"], refs),
            "size": "2K",
            "response_format": "b64_json",
            "watermark": False,
            "sequential_image_generation": "disabled",
        }
        if refs:
            assets = _asset_map(refs)
            body["image"] = [_ref_url(r, assets) for r in refs]
        resp = self._call("POST", "/api/v3/images/generations", key, body, timeout=180)
        data = resp.get("data") or []
        if not data or not data[0].get("b64_json"):
            raise Rejected("ModelArk không trả ảnh (thường do bộ lọc nội dung)")
        target = staging / f"{job['externalId']}.png"
        target.write_bytes(base64.b64decode(data[0]["b64_json"]))
        return GenerationResult(staged_file=target)

    # ---------- video: Seedance ----------
    def _video(self, job: dict, key: str, staging: Path, should_stop) -> GenerationResult:
        refs = job.get("referenceImages") or []
        if len(refs) > MAX_REFS:
            raise Rejected(f"Cảnh cần {len(refs)} ảnh tham chiếu, Seedance nhận tối đa {MAX_REFS}")
        content = [{"type": "text", "text": _with_ref_legend(job["prompt"], refs)}]
        assets = _asset_map(refs)
        content += [{"type": "image_url", "image_url": {"url": _ref_url(r, assets)}, "role": "reference_image"} for r in refs]
        model = job["account"].get("videoModel") or DEFAULT_VIDEO_MODEL
        body = {
            "model": model,
            "content": content,
            "ratio": job.get("aspectRatio") or "16:9",
            # Seedance 2.0 mini chỉ có 720p / 480p (Playground: Limitations): chọn 1080p thì hạ về 720p thay vì bị từ chối
            "resolution": "720p" if "mini" in model and job.get("videoQuality") == "1080p" else (job.get("videoQuality") or "720p"),
            "watermark": False,
        }
        if _AUDIO_MODEL_RE.search(model):   # model cũ không có tham số này
            body["generate_audio"] = GENERATE_AUDIO and job.get("generateAudio") is not False
        seconds = _duration(job)
        if seconds:
            body["duration"] = seconds
        task_id = self._call("POST", "/api/v3/contents/generations/tasks", key, body).get("id")
        if not task_id:
            raise TransientError("ModelArk không trả mã task")

        deadline = time.monotonic() + int(job.get("timeoutSeconds") or 900)
        while True:
            if should_stop():
                self._cancel(task_id, key)
                raise Cancelled("Người dùng hủy job")
            if time.monotonic() > deadline:
                raise TransientError(f"Quá thời gian chờ Seedance (task {task_id})")
            task = self._call("GET", f"/api/v3/contents/generations/tasks/{task_id}", key)
            status = (task.get("status") or "").lower()
            if status == "succeeded":
                break
            if status in ("failed", "cancelled", "expired"):
                err = task.get("error") or {}
                raise Rejected(f"Seedance báo {status}: {err.get('code') or ''} {err.get('message') or ''}".strip())
            time.sleep(POLL_SECONDS)

        content_out = task.get("content") or {}
        if isinstance(content_out, list):
            content_out = content_out[0] if content_out else {}
        url = content_out.get("video_url")
        if not url:
            raise TransientError(f"Task {task_id} xong nhưng không có link video")
        target = staging / f"{job['externalId']}.mp4"
        try:
            with self.http.get(url, stream=True, timeout=300) as r:
                r.raise_for_status()
                with open(target, "wb") as f:
                    for chunk in r.iter_content(1 << 20):
                        f.write(chunk)
        except requests.RequestException as e:
            raise TransientError(f"Tải video Seedance lỗi: {e}") from e
        return GenerationResult(staged_file=target, remote_ref=task_id)

    def _cancel(self, task_id: str, key: str):
        try:
            self._call("DELETE", f"/api/v3/contents/generations/tasks/{task_id}", key, timeout=20)
        except Exception:  # noqa: BLE001 - hủy được hay không cũng không đổi kết quả
            pass
