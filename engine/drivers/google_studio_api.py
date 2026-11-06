"""
Google AI Studio (Gemini Developer API) với API key từ aistudio.google.com.

Ảnh: Imagen 3 (mặc định imagen-3.0-generate-002)
Video: Veo 2 (mặc định veo-2.0-generate-001)

API key được lấy từ mục Tài khoản (trường apiKey). Model video và model ảnh có thể cấu hình
riêng từng tài khoản hoặc dùng biến môi trường AFM_STUDIO_IMAGE_MODEL / AFM_STUDIO_VIDEO_MODEL.
"""
import base64
import os
import time
from pathlib import Path

import requests

from .errors import AuthRequired, Cancelled, ConfigError, GenerationResult, QuotaExceeded, Rejected, TransientError

DEFAULT_IMAGE_MODEL = os.environ.get("AFM_STUDIO_IMAGE_MODEL", "imagen-3.0-generate-002")
DEFAULT_VIDEO_MODEL = os.environ.get("AFM_STUDIO_VIDEO_MODEL", "veo-2.0-generate-001")
MAX_VIDEO_REFS = int(os.environ.get("AFM_STUDIO_MAX_REFS", "3"))
BASE_REST_URL = os.environ.get("AFM_GEMINI_BASE_URL", "https://generativelanguage.googleapis.com").rstrip("/")
POLL_SECONDS = 10


class GoogleStudioDriver:
    def __init__(self, http=None):
        self.http = http or requests.Session()

    @staticmethod
    def _key(acc: dict) -> str:
        key = (acc or {}).get("apiKey")
        if not key:
            raise ConfigError(f"Tài khoản {(acc or {}).get('email')} chưa nhập API key Google AI Studio")
        return key.strip()

    def generate_foreground(self, job, should_stop):
        raise Rejected("Google AI Studio (API) không có chế độ FOREGROUND: đổi phim sang chạy nền")

    def generate(self, job: dict, should_stop) -> GenerationResult:
        staging = Path(job["stagingDir"])
        staging.mkdir(parents=True, exist_ok=True)
        key = self._key(job.get("account"))
        if job["kind"] == "IMAGE":
            return self._image(job, key, staging)
        return self._video(job, key, staging, should_stop)

    # ---------- Ảnh: Imagen 3 ----------
    def _image(self, job: dict, key: str, staging: Path) -> GenerationResult:
        acc = job.get("account") or {}
        model = acc.get("imageModel") or DEFAULT_IMAGE_MODEL
        prompt = job["prompt"]
        ratio = job.get("aspectRatio") or "16:9"

        # Thử qua google.genai nếu có, nếu không thì fallback REST trực tiếp
        try:
            from google import genai
            from google.genai import errors, types
            client = genai.Client(api_key=key)
            try:
                resp = client.models.generate_images(
                    model=model,
                    prompt=prompt,
                    config=types.GenerateImagesConfig(
                        number_of_images=1,
                        aspect_ratio=ratio,
                        output_mime_type="image/png",
                    ),
                )
                if not resp.generated_images:
                    raise Rejected("Google AI Studio không trả ảnh (thường do bộ lọc an toàn)")
                target = staging / f"{job['externalId']}.png"
                target.write_bytes(resp.generated_images[0].image.image_bytes)
                return GenerationResult(staged_file=target)
            except errors.ClientError as e:
                self._map_genai_error(e)
            except errors.ServerError as e:
                raise TransientError(f"Lỗi máy chủ Google AI Studio: {e}") from e
        except ImportError:
            pass

        # Fallback REST qua HTTP requests
        return self._image_rest(job, key, model, prompt, ratio, staging)

    def _image_rest(self, job: dict, key: str, model: str, prompt: str, ratio: str, staging: Path) -> GenerationResult:
        url = f"{BASE_REST_URL}/v1beta/models/{model}:predict"
        headers = {
            "x-goog-api-key": key,
            "Content-Type": "application/json",
        }
        body = {
            "instances": [{"prompt": prompt}],
            "parameters": {
                "sampleCount": 1,
                "aspectRatio": ratio,
                "outputMimeType": "image/png",
            },
        }
        try:
            r = self.http.post(url, headers=headers, json=body, timeout=120)
        except requests.RequestException as e:
            raise TransientError(f"Không gọi được Google AI Studio: {e}") from e

        if r.status_code >= 400:
            self._map_http_error(r)

        data = r.json() if r.content else {}
        predictions = data.get("predictions") or []
        if not predictions or not predictions[0].get("bytesBase64Encoded"):
            raise Rejected("Google AI Studio không trả ảnh (thường do bộ lọc an toàn)")

        target = staging / f"{job['externalId']}.png"
        staging.mkdir(parents=True, exist_ok=True)
        target.write_bytes(base64.b64decode(predictions[0]["bytesBase64Encoded"]))
        return GenerationResult(staged_file=target)

    # ---------- Video: Veo 2 ----------
    def _video(self, job: dict, key: str, staging: Path, should_stop) -> GenerationResult:
        acc = job.get("account") or {}
        model = acc.get("videoModel") or DEFAULT_VIDEO_MODEL
        refs = job.get("referenceImages") or []
        if len(refs) > MAX_VIDEO_REFS:
            raise Rejected(f"Cảnh cần {len(refs)} ảnh reference, Veo nhận tối đa {MAX_VIDEO_REFS}; hãy dùng FLOW_BROWSER")

        try:
            from google import genai
            from google.genai import errors, types
        except ImportError as e:
            raise ConfigError("Cần cài đặt thư viện google-genai để tạo video bằng Google AI Studio (Veo)") from e

        client = genai.Client(api_key=key)
        ref_images = [
            types.VideoGenerationReferenceImage(
                image=types.Image.from_file(location=p),
                reference_type="asset",
            )
            for p in refs
        ]
        config = types.GenerateVideosConfig(
            number_of_videos=1,
            aspect_ratio=job.get("aspectRatio") or "16:9",
            reference_images=ref_images or None,
            duration_seconds=job.get("durationSeconds") or None,
        )

        try:
            op = client.models.generate_videos(model=model, prompt=job["prompt"], config=config)
        except errors.ClientError as e:
            self._map_genai_error(e)
        except errors.ServerError as e:
            raise TransientError(f"Lỗi máy chủ Google AI Studio: {e}") from e

        deadline = time.monotonic() + int(job.get("timeoutSeconds") or 900)
        while not op.done:
            if should_stop():
                raise Cancelled("Người dùng hủy job")
            if time.monotonic() > deadline:
                raise TransientError("Quá thời gian chờ Veo (Google AI Studio)")
            time.sleep(POLL_SECONDS)
            try:
                op = client.operations.get(op)
            except errors.ServerError as e:
                # Lỗi mạng / máy chủ tạm thời khi polling thì chờ tiếp
                time.sleep(5)
                continue
            except errors.ClientError as e:
                self._map_genai_error(e)

        if op.error:
            raise Rejected(f"Google AI Studio (Veo) báo lỗi: {op.error}")

        videos = op.response.generated_videos if op.response else None
        if not videos:
            raise Rejected("Veo không trả video (thường do bộ lọc an toàn)")

        target = staging / f"{job['externalId']}.mp4"
        video = videos[0].video
        if hasattr(video, "save"):
            video.save(str(target))
        elif hasattr(video, "video_bytes") and video.video_bytes:
            target.write_bytes(video.video_bytes)
        else:
            raise TransientError("Không thể lưu video từ phản hồi Google AI Studio")

        return GenerationResult(staged_file=target)

    # ---------- Xử lý lỗi ----------
    @staticmethod
    def _map_genai_error(e):
        code = getattr(e, "code", None)
        msg = str(e)
        if code == 429 or "RESOURCE_EXHAUSTED" in msg:
            raise QuotaExceeded(f"Google AI Studio hết hạn ngạch hoặc bị giới hạn tần suất: {msg}") from e
        if code in (401, 403) or "API_KEY_INVALID" in msg:
            raise AuthRequired(f"API key Google AI Studio không đúng hoặc hết hạn ({msg})") from e
        raise Rejected(f"Google AI Studio từ chối yêu cầu: {msg}") from e

    @staticmethod
    def _map_http_error(r: requests.Response):
        try:
            err = (r.json() or {}).get("error") or {}
        except Exception:
            err = {}
        code = err.get("code") or r.status_code
        status = err.get("status") or ""
        msg = err.get("message") or r.text[:500]
        text = f"Google AI Studio {code} {status}: {msg}".strip()

        if code == 429 or status == "RESOURCE_EXHAUSTED" or "quota" in msg.lower():
            raise QuotaExceeded(text)
        if code in (401, 403) or "API_KEY_INVALID" in text:
            raise AuthRequired(f"API key Google AI Studio không đúng hoặc hết hạn ({text})")
        if code >= 500:
            raise TransientError(text)
        raise Rejected(text)
