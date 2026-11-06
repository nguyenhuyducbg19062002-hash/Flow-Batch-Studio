"""
Imagen / Veo qua Vertex AI (API chính thức, tùy chọn).

Lưu ý:
- Tên model và giới hạn thay đổi theo thời gian: kiểm lại tài liệu Vertex AI hiện hành, đổi qua biến môi trường.
- Veo nhận ít ảnh reference hơn mức 7 ảnh prompt Omni dùng: job vượt giới hạn bị REJECTED (không cắt bớt ảnh âm thầm).
- Cần cài thêm: google-genai, google-auth (không có trong bản đóng gói mặc định).
"""
import os
import time
from pathlib import Path

from .errors import AuthRequired, Cancelled, ConfigError, GenerationResult, QuotaExceeded, Rejected, TransientError

IMAGE_MODEL = os.environ.get("AFM_IMAGE_MODEL", "imagen-4.0-generate-001")
VIDEO_MODEL = os.environ.get("AFM_VIDEO_MODEL", "veo-3.1-generate-001")
MAX_VIDEO_REFS = int(os.environ.get("AFM_VEO_MAX_REFS", "3"))


class VertexDriver:
    def __init__(self):
        try:
            from google import genai  # noqa: F401
            from google.oauth2 import service_account  # noqa: F401
        except ImportError as e:
            raise ConfigError("Chưa cài google-genai / google-auth cho VERTEX_API") from e

    @staticmethod
    def _client(acc: dict):
        from google import genai
        from google.oauth2 import service_account

        if not acc.get("credentialFile") or not acc.get("gcpProject") or not acc.get("gcpLocation"):
            raise ConfigError(f"Tài khoản {acc.get('email')} thiếu credentialFile / gcpProject / gcpLocation")
        creds = service_account.Credentials.from_service_account_file(
            acc["credentialFile"], scopes=["https://www.googleapis.com/auth/cloud-platform"])
        return genai.Client(vertexai=True, project=acc["gcpProject"], location=acc["gcpLocation"], credentials=creds)

    def generate_foreground(self, job, should_stop):
        raise Rejected("VERTEX_API không có chế độ FOREGROUND")

    def generate(self, job: dict, should_stop) -> GenerationResult:
        from google.genai import errors

        staging = Path(job["stagingDir"])
        staging.mkdir(parents=True, exist_ok=True)
        try:
            client = self._client(job["account"])
            if job["kind"] == "IMAGE":
                return self._image(client, job, staging)
            return self._video(client, job, staging, should_stop)
        except errors.ClientError as e:
            if e.code == 429:
                raise QuotaExceeded(str(e)) from e
            if e.code in (401, 403):
                raise AuthRequired(str(e)) from e
            raise Rejected(str(e)) from e
        except errors.ServerError as e:
            raise TransientError(str(e)) from e

    @staticmethod
    def _image(client, job, staging) -> GenerationResult:
        from google.genai import types

        resp = client.models.generate_images(
            model=IMAGE_MODEL, prompt=job["prompt"],
            config=types.GenerateImagesConfig(number_of_images=1, aspect_ratio=job.get("aspectRatio") or "16:9"))
        if not resp.generated_images:
            raise Rejected("Vertex không trả ảnh (thường do bộ lọc an toàn)")
        target = staging / f"{job['externalId']}.png"
        target.write_bytes(resp.generated_images[0].image.image_bytes)
        return GenerationResult(staged_file=target)

    @staticmethod
    def _video(client, job, staging, should_stop) -> GenerationResult:
        from google.genai import types

        refs = job["referenceImages"]
        if len(refs) > MAX_VIDEO_REFS:
            raise Rejected(f"Segment cần {len(refs)} ảnh reference, Veo nhận tối đa {MAX_VIDEO_REFS}; hãy dùng FLOW_BROWSER")
        ref_images = [types.VideoGenerationReferenceImage(image=types.Image.from_file(location=p), reference_type="asset")
                      for p in refs]
        config = types.GenerateVideosConfig(number_of_videos=1, aspect_ratio=job.get("aspectRatio") or "16:9",
                                            reference_images=ref_images or None,
                                            duration_seconds=job.get("durationSeconds") or None)
        op = client.models.generate_videos(model=VIDEO_MODEL, prompt=job["prompt"], config=config)
        deadline = time.monotonic() + job["timeoutSeconds"]
        while not op.done:
            if should_stop():
                raise Cancelled("Người dùng hủy job")
            if time.monotonic() > deadline:
                raise TransientError("Quá thời gian chờ Veo")
            time.sleep(15)
            op = client.operations.get(op)
        if op.error:
            raise Rejected(str(op.error))
        videos = op.response.generated_videos if op.response else None
        if not videos:
            raise Rejected("Veo không trả video (thường do bộ lọc an toàn)")
        video = videos[0].video
        if not video.video_bytes:
            raise TransientError(f"Video nằm ở {video.uri}: cần cấu hình output_gcs_uri và tải từ GCS")
        target = staging / f"{job['externalId']}.mp4"
        target.write_bytes(video.video_bytes)
        return GenerationResult(staged_file=target, remote_ref=op.name)
