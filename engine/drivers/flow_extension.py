"""
FLOW_EXTENSION: tạo ảnh / video trên Google Flow qua extension "Flow Batch Studio – Flow Bridge" trong Chrome.

Extension chạy trong tab flow.google.com đã đăng nhập, gọi thẳng API batchexecute của Flow (không bấm giao diện).
Driver này dựng lệnh (drivers/flow_batch.py), gửi qua bridge (flow_ext/bridge.py) và đọc kết quả:

  ảnh tham chiếu  -> maseQ (upload, nhớ media id theo file để lần sau không upload lại)
  IMAGE           -> ogiZ0b (Nano Banana Pro), ảnh tham chiếu đi kèm theo đúng thứ tự
  VIDEO có ảnh    -> MZZa6b (Omni Flash, 1–7 ảnh tham chiếu) -> jwpduf / Zzl0ze -> as29s
  VIDEO không ảnh -> YhhmEf (Omni Flash text-to-video) -> as29s
  tải file        -> GET địa chỉ flow-content.google đã ký sẵn

Dự án Flow: lấy từ "Link dự án Flow" của phim nếu có; không có thì tạo một dự án và nhớ lại (theo tài khoản).
Giao thức tham khảo FlowKit (https://github.com/crisng95/flowkit, MIT).
"""
from __future__ import annotations

import base64
import json
import logging
import mimetypes
import os
import re
import threading
import time
from pathlib import Path

import requests

from drivers import flow_batch as fb
from drivers.errors import (AuthRequired, BotSuspected, Cancelled, ConfigError, GenerationResult, QuotaExceeded,
                            Rejected, TransientError)
from flow_ext import bridge

log = logging.getLogger("flow-extension")

UUID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
MAX_VIDEO_REFS = 7
OMNI_DURATIONS = (4, 6, 8, 10)
POLL_INTERVAL_S = 10
IMAGE_MODEL = os.environ.get("AVS_FLOW_IMAGE_MODEL", fb.IMAGE_MODEL)
CAPTCHA_RETRIES = 3
NOT_FOUND_GRACE_S = int(os.environ.get("AVS_FLOW_NOT_FOUND_GRACE_S", "180"))
# extension chưa kết nối / vừa rớt (Chrome đóng, service worker khởi động lại): chờ nó nối lại thay vì báo lỗi ngay
EXTENSION_WAIT_S = int(os.environ.get("AVS_FLOW_EXTENSION_WAIT_S", "180"))


def _data_dir() -> Path:
    base = os.environ.get("LOCALAPPDATA")
    d = Path(base, "FlowBatchStudio") if base else Path(__file__).resolve().parents[1] / "data"
    d.mkdir(parents=True, exist_ok=True)
    return d


class _JsonCache:
    """Bảng nhớ nhỏ dùng chung giữa các worker (ghi cả file mỗi lần, có khoá trong tiến trình)."""

    def __init__(self, name: str):
        self.path = _data_dir() / name
        self.lock = threading.Lock()

    def get(self, key: str):
        with self.lock:
            return self._load().get(key)

    def put(self, key: str, value):
        with self.lock:
            data = self._load()
            if value is None:
                data.pop(key, None)
            else:
                data[key] = value
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
            os.replace(tmp, self.path)

    def _load(self) -> dict:
        try:
            return json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}


MEDIA_CACHE = _JsonCache("flow_ext_media.json")
PROJECT_CACHE = _JsonCache("flow_ext_projects.json")


def classify(message: str) -> Exception:
    """Đổi thông báo lỗi của Flow / extension thành lỗi backend hiểu được."""
    m = message or ""
    if "UNUSUAL_ACTIVITY" in m or "FLOW_HOLD" in m or "extension_hijack_detected" in m:
        return BotSuspected(f"Flow nghi ngờ tự động hoá: {m[:400]}")
    if "QUOTA_REACHED" in m or "RESOURCE_EXHAUSTED" in m or "INSUFFICIENT_CREDITS" in m:
        return QuotaExceeded(m[:500])
    if "UNSAFE" in m or "PUBLIC_ERROR_" in m and ("POLICY" in m or "PROMINENT_PEOPLE" in m or "MINOR" in m):
        return Rejected(f"Flow từ chối nội dung: {m[:500]}")
    if "NO_AT_TOKEN" in m or "SIGN_IN" in m.upper() or "status 401" in m:
        return AuthRequired("Tab Flow chưa đăng nhập Google (hoặc phiên hết hạn): mở flow.google.com và đăng nhập lại")
    if "NO_EXTENSION" in m:
        return TransientError("Chưa có extension Flow Bridge nào kết nối: mở Chrome có cài extension "
                              "và một tab flow.google.com đã đăng nhập")
    return TransientError(m[:1500])


class FlowExtensionDriver:
    _submitted = False   # lệnh tạo của job hiện tại đã được Flow nhận chưa (đặt lại ở đầu generate)

    def generate_foreground(self, job, should_stop):
        raise Rejected("FLOW_EXTENSION chỉ có chế độ chạy nền")

    # ── điểm vào ────────────────────────────────────────────────────────────

    def generate(self, job: dict, should_stop) -> GenerationResult:
        staging = Path(job["stagingDir"])
        staging.mkdir(parents=True, exist_ok=True)
        try:
            bridge.ensure_bridge()
        except RuntimeError as e:
            raise ConfigError(str(e)) from e
        self._wait_extension(should_stop)
        project_id = self._project_id(job)
        refs = [Path(p) for p in (job.get("referenceImages") or [])]
        missing = [str(p) for p in refs if not p.is_file()]
        if missing:
            raise TransientError(f"Thiếu file ảnh tham chiếu: {', '.join(missing[:5])}")
        self._submitted = False
        try:
            return self._generate(job, project_id, refs, staging, should_stop)
        except fb.RpcError as e:
            # Chỉ upload lại + gửi lại khi lệnh tạo CHƯA được Flow nhận. Đã gửi rồi mà gửi lại là tạo trùng (tốn lượt).
            if self._media_gone(e) and refs and not self._submitted:
                # media id đã nhớ bị Flow xoá/hết hạn: quên đi rồi upload lại một lần
                for p in refs:
                    MEDIA_CACHE.put(self._media_key(project_id, p), None)
                return self._generate(job, project_id, refs, staging, should_stop)
            raise classify(str(e)) from e

    def _generate(self, job, project_id, refs, staging, should_stop) -> GenerationResult:
        media_ids = [self._upload(project_id, p) for p in refs]
        if should_stop():
            raise Cancelled("Người dùng hủy job")
        if job["kind"] == "IMAGE":
            return self._image(job, project_id, media_ids, staging)
        return self._video(job, project_id, media_ids, staging, should_stop)

    @staticmethod
    def _media_gone(e: fb.RpcError) -> bool:
        return "not found" in str(e).lower() or e.detail == [5]

    # ── gọi extension ───────────────────────────────────────────────────────

    @staticmethod
    def _connected() -> bool:
        try:
            return bool(bridge.status().get("connected"))
        except Exception:
            return False

    def _wait_extension(self, should_stop=lambda: False, wait_s: int | None = None):
        if self._connected():
            return
        if wait_s is None:
            wait_s = EXTENSION_WAIT_S
        log.info("Chờ extension Flow Bridge kết nối (tối đa %s giây)…", wait_s)
        deadline = time.monotonic() + wait_s
        while time.monotonic() < deadline:
            if should_stop():
                raise Cancelled("Người dùng hủy job")
            time.sleep(3)
            if self._connected():
                log.info("Extension đã kết nối")
                return
        raise TransientError("Chưa có extension Flow Bridge nào kết nối: mở Chrome có cài extension "
                             "và một tab flow.google.com đã đăng nhập")

    def _call(self, rpcid: str, freq: str, captcha: str | None = None, match: str | None = None,
              timeout: float = 120) -> str:
        """Gửi lệnh, trả nguyên văn phản hồi batchexecute. Lỗi captcha thì thử lại vài lần."""
        last = ""
        for attempt in range(CAPTCHA_RETRIES if captcha else 2):
            try:
                out = bridge.rpc(rpcid, freq, captcha, match, timeout)
            except requests.RequestException as e:
                raise TransientError(f"Không gọi được Flow bridge: {e}") from e
            err = out.get("error")
            if not err:
                status = int(out.get("status") or 0)
                data = out.get("data") or ""
                if status in (401, 403):
                    raise AuthRequired(f"Flow trả {status}: tab Flow chưa đăng nhập hoặc phiên hết hạn")
                if status >= 400 and not data.startswith(")]}'"):
                    raise classify(f"{rpcid} status {status}: {data[:500]}")
                if "PUBLIC_ERROR_" in data and rpcid != fb.RPC_PROJECT_MEDIA:
                    found = re.search(r"PUBLIC_ERROR_[A-Z_]+", data)
                    if found and ("UNUSUAL" in found.group(0) or "QUOTA" in found.group(0)
                                  or "UNSAFE" in found.group(0)):
                        raise classify(found.group(0))
                return data
            last = str(err)
            # chưa gửi được (NO_EXTENSION), hoặc rớt khi đang hỏi trạng thái (không tốn lượt tạo): chờ nối lại rồi gửi lại.
            # Lệnh tạo đã gửi đi mà rớt thì không gửi lại, tránh tạo trùng: báo lỗi để backend thử lại cả cảnh.
            if "NO_EXTENSION" in last or ("EXTENSION_DISCONNECTED" in last and not captcha):
                self._wait_extension()
                continue
            if not ("CAPTCHA" in last or "grecaptcha" in last or "CONTENT_TIMEOUT" in last):
                raise classify(last)
            log.warning("%s: lỗi captcha (%s), thử lại %s", rpcid, last, attempt + 1)
            time.sleep(3 + attempt * 3)
        raise TransientError(f"Gửi lệnh {rpcid} cho extension không thành công: {last}")

    def _payload(self, rpcid: str, freq: str, captcha: str | None = None, timeout: float = 120):
        return fb.first_payload(self._call(rpcid, freq, captcha, timeout=timeout), rpcid)

    # ── dự án Flow ──────────────────────────────────────────────────────────

    def _project_id(self, job: dict) -> str:
        found = UUID_RE.search(job.get("flowProjectUrl") or "")
        if found:
            return found.group(0)
        env = os.environ.get("AVS_FLOW_PROJECT_ID") or ""
        if UUID_RE.fullmatch(env):
            return env
        key = str((job.get("account") or {}).get("id") or "default")
        cached = PROJECT_CACHE.get(key)
        if cached:
            return cached
        payload = self._payload(fb.RPC_CREATE_PROJECT, fb.create_project_request("Flow Batch Studio"))
        project_id, _ = fb.read_created_project(payload)
        PROJECT_CACHE.put(key, project_id)
        log.info("Đã tạo dự án Flow %s cho tài khoản %s", project_id, key)
        return project_id

    # ── ảnh tham chiếu ──────────────────────────────────────────────────────

    @staticmethod
    def _media_key(project_id: str, p: Path) -> str:
        st = p.stat()
        return f"{project_id}|{p.resolve()}|{st.st_size}|{int(st.st_mtime)}"

    def _upload(self, project_id: str, p: Path) -> str:
        key = self._media_key(project_id, p)
        cached = MEDIA_CACHE.get(key)
        if cached:
            return cached
        mime = mimetypes.guess_type(p.name)[0] or "image/png"
        if not mime.startswith("image/"):
            raise Rejected(f"Ảnh tham chiếu không phải file ảnh: {p.name}")
        b64 = base64.b64encode(p.read_bytes()).decode("ascii")
        payload = self._payload(fb.RPC_UPLOAD_IMAGE, fb.upload_request(b64, project_id, mime, p.name),
                                fb.CAPTCHA_IMAGE)
        media_id = fb.read_uploaded_media_id(payload)
        MEDIA_CACHE.put(key, media_id)
        log.info("Upload %s -> %s", p.name, media_id)
        return media_id

    # ── ảnh ─────────────────────────────────────────────────────────────────

    def _image(self, job, project_id, media_ids, staging) -> GenerationResult:
        aspect = job.get("aspectRatio") or "16:9"
        if aspect not in fb.ASPECT_BY_NAME:
            aspect = "16:9"
        freq = fb.image_request(job["prompt"], project_id, count=1, aspect=aspect, model=IMAGE_MODEL,
                                ref_media_ids=media_ids or None)
        payload = self._payload(fb.RPC_GEN_IMAGE, freq, fb.CAPTCHA_IMAGE, timeout=180)
        images = fb.read_images(payload)
        if not images:
            raise Rejected("Flow không trả ảnh (thường do bộ lọc an toàn)")
        img = images[0]
        data = self._download(img.url, lambda: img.url)
        ext = ".jpg" if data[:3] == b"\xff\xd8\xff" else ".webp" if data[8:12] == b"WEBP" else ".png"
        target = staging / f"{job['externalId']}{ext}"
        target.write_bytes(data)
        return GenerationResult(staged_file=target, remote_ref=img.media_id)

    # ── video ───────────────────────────────────────────────────────────────

    @staticmethod
    def _duration(job) -> int:
        want = int(job.get("durationSeconds") or 8)
        return min(OMNI_DURATIONS, key=lambda d: (abs(d - want), -d))

    @staticmethod
    def _resolution(job) -> str:
        return "360p" if str(job.get("videoQuality") or "").lower() in ("360p", "480p") else "720p"

    def _video(self, job, project_id, media_ids, staging, should_stop) -> GenerationResult:
        if len(media_ids) > MAX_VIDEO_REFS:
            raise Rejected(f"Cảnh có {len(media_ids)} ảnh tham chiếu, Flow Omni nhận tối đa {MAX_VIDEO_REFS}")
        aspect = fb.VIDEO_ASPECT_PORTRAIT if (job.get("aspectRatio") or "") in ("9:16", "3:4") \
            else fb.VIDEO_ASPECT_LANDSCAPE
        duration, res = self._duration(job), self._resolution(job)
        deadline = time.monotonic() + max(int(job.get("timeoutSeconds") or 600), 120)

        if media_ids:
            freq = fb.omni_reference_video_request(job["prompt"], project_id, media_ids,
                                                   duration_s=duration, resolution=res, aspect=aspect)
            payload = self._payload(fb.RPC_GEN_VIDEO_REFERENCES, freq, fb.CAPTCHA_VIDEO, timeout=180)
            self._submitted = True
            op = fb.read_operation(payload)
            media_id = self._wait_operation(op.operation_id, project_id, deadline, should_stop)
        else:
            freq = fb.text_video_request(job["prompt"], project_id, aspect=aspect,
                                         model=f"abra_t2v_{duration}s", resolution=res)
            payload = self._payload(fb.RPC_GEN_VIDEO_TEXT, freq, fb.CAPTCHA_VIDEO, timeout=180)
            self._submitted = True
            media_id = fb.read_text_video_submit(payload)["media_id"]

        url = self._wait_video_url(media_id, deadline, should_stop)
        data = self._download(url, lambda: self._video_url(media_id))
        target = staging / f"{job['externalId']}.mp4"
        target.write_bytes(data)
        return GenerationResult(staged_file=target, remote_ref=media_id)

    def _wait_operation(self, operation_id: str, project_id: str, deadline: float, should_stop) -> str:
        """Chờ tới khi danh sách media của dự án có media id cho lệnh này."""
        rounds = 0
        while True:
            self._tick(deadline, should_stop)
            rounds += 1
            look = rounds % 3 == 0
            try:
                op = fb.read_operation(self._payload(fb.RPC_OPERATION, fb.operation_request(operation_id), timeout=60))
                if op.error and "not found" not in op.error.lower():
                    log.info("Lệnh %s báo: %s", operation_id[:12], op.error)
                look = look or op.done or op.complained
            except (fb.FlowBatchError, fb.RpcError, ValueError) as e:
                log.debug("Không đọc được trạng thái %s: %s", operation_id[:12], e)
                look = True
            if look:
                raw = self._call(fb.RPC_PROJECT_MEDIA, fb.project_media_request(project_id),
                                 match=operation_id, timeout=120)
                media_id = fb.find_media_id_in_text(raw, operation_id)
                if media_id:
                    return media_id
            time.sleep(POLL_INTERVAL_S)

    def _video_url(self, media_id: str) -> str | None:
        payload = self._payload(fb.RPC_MEDIA, fb.media_request(media_id), timeout=60)
        return fb.read_media_urls(payload, media_id).video

    def _wait_video_url(self, media_id: str, deadline: float, should_stop) -> str:
        # as29s trả NOT_FOUND ([5]) quá NOT_FOUND_GRACE_S thì coi như Flow không tạo ra video (đã gặp thật: dự án chỉ có
        # ảnh upload, không có video) -> báo lỗi thay vì chờ tới hết job_timeout. Không tự gửi lại lệnh tạo.
        not_found_until = time.monotonic() + NOT_FOUND_GRACE_S
        while True:
            try:
                url = self._video_url(media_id)
                if url:
                    return url
            except fb.FlowBatchError as e:
                log.debug("Media %s chưa sẵn: %s", media_id[:12], e)
            except fb.RpcError as e:
                if not self._media_gone(e):
                    raise
                log.info("Video %s: Flow trả NOT_FOUND (%s)", media_id[:12], e)
                if time.monotonic() > not_found_until:
                    raise TransientError(f"Flow nhận lệnh nhưng không có video {media_id} trong dự án "
                                         f"sau {NOT_FOUND_GRACE_S} giây") from e
            self._tick(deadline, should_stop)
            time.sleep(POLL_INTERVAL_S)

    @staticmethod
    def _tick(deadline: float, should_stop):
        if should_stop():
            raise Cancelled("Người dùng hủy job")
        if time.monotonic() > deadline:
            raise TransientError("Quá thời gian chờ Flow tạo xong")

    # ── tải file ────────────────────────────────────────────────────────────

    @staticmethod
    def _download(url: str, resign) -> bytes:
        for attempt in range(3):
            try:
                r = requests.get(url, timeout=300)
            except requests.RequestException as e:
                if attempt == 2:
                    raise TransientError(f"Tải kết quả từ Flow lỗi: {e}") from e
                time.sleep(3)
                continue
            if r.status_code in (400, 403) and attempt < 2:
                url = resign() or url    # địa chỉ ký sẵn hết hạn: xin lại
                continue
            if not r.ok:
                raise TransientError(f"Tải kết quả từ Flow lỗi HTTP {r.status_code}")
            if len(r.content) < 1024:
                raise TransientError("File tải về quá nhỏ (hỏng)")
            return r.content
        raise TransientError("Không tải được kết quả từ Flow")
