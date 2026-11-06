package com.aifilm.automation.account;

import com.aifilm.automation.account.LoginChecker.Result;

/**
 * Từ kết quả kiểm tra đăng nhập quyết định trạng thái tài khoản (logic thuần, có test).
 * Nguyên tắc: kiểm tra chỉ ĐƯA LÊN "Sẵn sàng" tài khoản đang "Cần đăng nhập" và chỉ HẠ xuống "Cần đăng nhập" tài khoản đang
 * "Sẵn sàng" khi chắc chắn là chưa đăng nhập / sai tài khoản. Tài khoản "Tạm nghỉ" (hết quota) hoặc "Đã tắt" do người dùng quyết định
 * thì không bị lần kiểm tra này đụng tới (dùng nút "Bật lại"). Không kết luận được (đang mở cửa sổ, lỗi mạng…) thì giữ nguyên.
 */
public final class LoginCheckPolicy {

    /** newStatus = null: giữ nguyên trạng thái. */
    public record Decision(AccountStatus newStatus, String message) {}

    private LoginCheckPolicy() {}

    public static Decision decide(AccountStatus current, String accountEmail, Result r) {
        boolean flexible = current == AccountStatus.NEED_LOGIN || current == AccountStatus.READY;
        switch (r.state()) {
            case "logged_in": {
                String email = r.email() == null ? "" : r.email().trim();
                if (!email.isEmpty() && accountEmail != null && !email.equalsIgnoreCase(accountEmail.trim())) {
                    String msg = "Profile đang đăng nhập bằng " + email + ", không phải " + accountEmail
                            + ". Bấm \"Đăng nhập\" rồi đăng nhập đúng tài khoản.";
                    return new Decision(flexible ? AccountStatus.NEED_LOGIN : null, msg);
                }
                if (current == AccountStatus.NEED_LOGIN) {
                    return new Decision(AccountStatus.READY, "Đã đăng nhập" + (email.isEmpty() ? "" : " (" + email + ")") + ": tài khoản sẵn sàng");
                }
                if (current == AccountStatus.READY) {
                    return new Decision(null, "Tài khoản vẫn đang đăng nhập" + (email.isEmpty() ? "" : " (" + email + ")"));
                }
                String why = current == AccountStatus.COOLDOWN ? "đang tạm nghỉ" : "đã bị tắt";
                return new Decision(null, "Đã đăng nhập, nhưng tài khoản " + why + ": dùng nút \"Bật lại\" nếu muốn chạy tiếp");
            }
            case "logged_out":
                return new Decision(flexible ? AccountStatus.NEED_LOGIN : null,
                        "Chưa đăng nhập Google Flow: bấm \"Đăng nhập\" rồi đăng nhập trong cửa sổ Chrome");
            case "in_use":
                return new Decision(null, "Cửa sổ Chrome của tài khoản còn mở: đăng nhập xong thì ĐÓNG cửa sổ đó rồi kiểm tra lại");
            default:
                return new Decision(null, "Chưa kiểm tra được: " + (r.message() == null || r.message().isBlank() ? "lỗi không rõ" : r.message()));
        }
    }
}
