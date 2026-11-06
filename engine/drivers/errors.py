"""Mã lỗi dùng chung với backend Java (com.aifilm.automation.job.ErrorCode)."""
from dataclasses import dataclass
from pathlib import Path


class WorkerError(Exception):
    code = "TRANSIENT"


class TransientError(WorkerError):
    """Lỗi tạm thời (mạng, timeout, trang chưa tải xong): backend thử lại tới max_attempts."""
    code = "TRANSIENT"


class QuotaExceeded(WorkerError):
    """Hết quota/credit: backend cho tài khoản nghỉ và chuyển job sang tài khoản khác."""
    code = "QUOTA_EXCEEDED"


class BotSuspected(WorkerError):
    """Flow báo "We noticed some unusual activity" (nghi tự động hóa / gửi quá nhanh): tài khoản cần nghỉ một lúc,
    job không bị tính lượt thử mà chuyển sang tài khoản khác hoặc chạy lại sau."""
    code = "SUSPICIOUS"


class AuthRequired(WorkerError):
    """Phiên đăng nhập hỏng / checkpoint: tài khoản cần đăng nhập lại."""
    code = "AUTH_REQUIRED"


class Rejected(WorkerError):
    """Nền tảng từ chối chính prompt/ảnh này (chính sách nội dung, tham số sai). Đổi tài khoản không giúp gì."""
    code = "REJECTED"


class Cancelled(WorkerError):
    code = "CANCELLED"


class ConfigError(WorkerError):
    """Worker chưa được cấu hình đúng (selector Flow...): backend dừng project thay vì đốt hết job."""
    code = "CONFIG_ERROR"


class PausedByUser(WorkerError):
    """Người dùng đóng cửa sổ Flow hoặc bấm "Tạm dừng phim": backend đưa cảnh về hàng đợi và tạm dừng phim."""
    code = "PAUSED"


class TabLost(PausedByUser):
    """Tab / cửa sổ Flow bị đóng giữa chừng. Driver extension tự mở lại tab Flow và làm lại cảnh vài lần trước khi dừng phim."""


class AccountBroken(WorkerError):
    """Tài khoản không dùng được vì cấu hình của chính nó (proxy sai / không kết nối được): backend tắt tài khoản."""
    code = "ACCOUNT_BROKEN"


# Lỗi mạng của Chromium/Edge do PROXY của tài khoản (không phải mạng chập chờn tạm thời)
PROXY_ERRORS = ("ERR_PROXY_CONNECTION_FAILED", "ERR_TUNNEL_CONNECTION_FAILED", "ERR_NO_SUPPORTED_PROXIES",
                "ERR_PROXY_AUTH_UNSUPPORTED", "ERR_PROXY_CERTIFICATE_INVALID", "ERR_SOCKS_CONNECTION_FAILED",
                "ERR_MANDATORY_PROXY_CONFIGURATION_FAILED", "ERR_PROXY_AUTH_REQUESTED")


def proxy_error(message: str) -> str | None:
    """Tên lỗi proxy nếu thông báo lỗi là do proxy, ngược lại None."""
    return next((code for code in PROXY_ERRORS if code in (message or "")), None)


@dataclass
class GenerationResult:
    staged_file: Path
    remote_ref: str | None = None
    # địa chỉ màn chỉnh sửa của kết quả trên Flow (…/edit/<media>): dùng cho "Sửa trên Flow"
    media_url: str | None = None
