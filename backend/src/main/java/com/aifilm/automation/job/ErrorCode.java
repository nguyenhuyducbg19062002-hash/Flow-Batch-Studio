package com.aifilm.automation.job;

/** Mã lỗi worker gửi về; trùng tên với engine/drivers/errors.py. */
public enum ErrorCode {
    /** Hết quota/credit: tài khoản nghỉ, job sang tài khoản khác, không tính attempt. */
    QUOTA_EXCEEDED,
    /** Flow báo "unusual activity" (nghi tự động hóa / gửi dồn dập): tài khoản nghỉ một lúc, job không tính lượt thử. */
    SUSPICIOUS,
    /** Phiên đăng nhập hỏng / checkpoint: tài khoản cần đăng nhập lại, job sang tài khoản khác. */
    AUTH_REQUIRED,
    /** Nền tảng từ chối chính prompt/ảnh này: đổi tài khoản không giúp gì, cần người sửa. */
    REJECTED,
    /** Lỗi tạm thời: thử lại tới max_attempts. */
    TRANSIENT,
    CANCELLED,
    /** Worker chưa được cấu hình đúng (selector Flow…): dừng project, không đốt hết job. */
    CONFIG_ERROR,
    /** Tài khoản không dùng được vì cấu hình của chính nó (proxy sai / không kết nối được): tắt tài khoản, job sang tài khoản khác. */
    ACCOUNT_BROKEN,
    /** Người dùng đóng cửa sổ Flow hoặc bấm "Tạm dừng phim": job về hàng đợi (không tính lỗi), phim tạm dừng. */
    PAUSED;

    public static ErrorCode parse(String s) {
        try {
            return valueOf(s);
        } catch (RuntimeException e) {
            return TRANSIENT;
        }
    }
}
