"""
Driver GIẢ để chạy thử toàn bộ vòng: claim → staging → duyệt → chép vào folder, mà không gọi nền tảng nào.

- IMAGE: tạo một ảnh PNG màu đơn sắc (không cần thư viện ngoài).
- VIDEO: tạo video 2 giây bằng ffmpeg nếu máy có ffmpeg; không có thì tạo ảnh PNG giữ chỗ.
- Tài khoản có email bắt đầu bằng "quota" luôn báo hết quota: dùng để thử cơ chế xoay vòng tài khoản.
- Prompt chứa "[fake:reject]" bị từ chối: dùng để thử nhánh FAILED → sửa prompt → tạo lại.
"""
import hashlib
import os
import random
import shutil
import struct
import subprocess
import time
import zlib
from pathlib import Path

from .errors import Cancelled, GenerationResult, QuotaExceeded, Rejected
from .flow_session import SessionRunner, Tile


def _png(path: Path, width: int, height: int, rgb: tuple[int, int, int]):
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    path.write_bytes(b"\x89PNG\r\n\x1a\n"
                     + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(raw, 9))
                     + chunk(b"IEND", b""))


def _color(text: str) -> tuple[int, int, int]:
    h = hashlib.sha1(text.encode("utf-8")).digest()
    return h[0], h[1], h[2]


class _FakeOps:
    """FlowOps giả cho SessionRunner: mỗi lệnh "tạo" trong vài giây, nhiều lệnh chồng lên nhau, xong lệch thứ tự."""

    def __init__(self, driver: "FakeDriver"):
        self.d = driver
        self.tiles: list[dict] = []   # mới nhất ở đầu, như lưới Flow thật

    def generated_tiles(self) -> list[Tile]:
        now = time.monotonic()
        return [Tile(key=f"fake-{t['owner']}" if now >= t["done_at"] else None, label=t["label"],
                     pct=None if now >= t["done_at"] else int((now - t["start"]) / (t["done_at"] - t["start"]) * 100),
                     ready=now >= t["done_at"]) for t in self.tiles]

    def submit(self, job: dict) -> None:
        if job["account"]["email"].lower().startswith("quota"):
            raise QuotaExceeded(f"[FAKE] {job['account']['email']} hết quota")
        if "[fake:reject]" in job["prompt"]:
            raise Rejected("[FAKE] prompt bị từ chối")
        now = time.monotonic()
        self.tiles.insert(0, {"owner": job["jobId"], "label": job["prompt"][:60], "start": now,
                              "done_at": now + random.uniform(*self.d.seconds)})

    def download(self, job: dict, tile: Tile) -> GenerationResult:
        assert tile.key == f"fake-{job['jobId']}", f"[FAKE] gắn nhầm kết quả: job {job['jobId']} nhận {tile.key}"
        return self.d._render(job)

    def page_error(self):
        return None

    def ensure_grid(self) -> None:
        pass


class FakeDriver:
    def __init__(self):
        # thời gian "tạo" của một lệnh trong phiên song song; đổi bằng biến môi trường AFM_FAKE_SECONDS="min,max"
        lo, _, hi = os.environ.get("AFM_FAKE_SECONDS", "3,7").partition(",")
        self.seconds = (float(lo), float(hi or lo))

    def run_session(self, first_job: dict, claim_more, on_start, on_done, on_fail, stop_of) -> None:
        capacity = int((first_job["account"] or {}).get("maxConcurrent") or 1)
        SessionRunner(_FakeOps(self), capacity, claim_more, on_start, on_done, on_fail, stop_of,
                      poll_s=0.3, gap_s=0.3).run(first_job)

    def generate(self, job: dict, should_stop) -> GenerationResult:
        if job["account"]["email"].lower().startswith("quota"):
            raise QuotaExceeded(f"[FAKE] {job['account']['email']} hết quota")
        if "[fake:reject]" in job["prompt"]:
            raise Rejected("[FAKE] prompt bị từ chối")
        for _ in range(6):  # giả lập thời gian render, vẫn phản hồi lệnh hủy
            if should_stop():
                raise Cancelled("Người dùng hủy job")
            time.sleep(0.5)
        return self._render(job)

    def _render(self, job: dict) -> GenerationResult:
        staging = Path(job["stagingDir"])
        staging.mkdir(parents=True, exist_ok=True)
        color = _color(job["prompt"])
        if job["kind"] == "IMAGE":
            target = staging / f"{job['externalId']}.png"
            _png(target, 320, 180, color)
            return GenerationResult(staged_file=target, remote_ref="fake://image")

        ffmpeg = shutil.which("ffmpeg")
        if not ffmpeg:
            # Không có ffmpeg: file MP4 giữ chỗ (hộp ftyp + mdat, không phát được) để app nhận đúng là video và vẫn
            # chạy được trọn vòng thư mục chờ duyệt → đổi tên (chỉ ở chế độ FAKE)
            target = staging / f"{job['externalId']}.mp4"
            ftyp = b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isomiso2"
            payload = bytes(color) * 200
            target.write_bytes(ftyp + (8 + len(payload)).to_bytes(4, "big") + b"mdat" + payload)
            return GenerationResult(staged_file=target, remote_ref="fake://video-placeholder")
        target = staging / f"{job['externalId']}.mp4"
        hex_color = "0x%02x%02x%02x" % color
        subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-f", "lavfi", "-i", f"color=c={hex_color}:s=640x360:d=2",
                        "-pix_fmt", "yuv420p", "-c:v", "libx264", str(target)], check=True, timeout=60)
        return GenerationResult(staged_file=target, remote_ref="fake://video")

    def generate_foreground(self, job: dict, should_stop) -> GenerationResult:
        return self.generate(job, should_stop)
