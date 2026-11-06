package com.aifilm.automation.account;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Lỗi đã gặp: "Đã đăng nhập xong" / "Bật lại" gọi releaseOk() nên tài khoản không bao giờ rời "Cần đăng nhập" / "Tạm nghỉ" / "Đã tắt". */
class AccountMarkReadyTest {

    private static AccountEntity in(AccountStatus status) {
        AccountEntity a = new AccountEntity();
        a.setStatus(status);
        a.setLastErrorMessage("lỗi cũ");
        a.setCooldownUntil(LocalDateTime.now().plusHours(1));
        a.setConsecutiveFailures(2);
        return a;
    }

    @Test
    void markReadyWorksFromEveryNonRunningStatus() {
        for (AccountStatus s : new AccountStatus[]{AccountStatus.NEED_LOGIN, AccountStatus.COOLDOWN, AccountStatus.DISABLED, AccountStatus.READY}) {
            AccountEntity a = in(s);
            a.markReady();
            assertEquals(AccountStatus.READY, a.getStatus(), "từ " + s);
            assertNull(a.getLastErrorMessage());
            assertNull(a.getCooldownUntil());
            assertEquals(0, a.getConsecutiveFailures());
        }
    }

    @Test
    void releaseOkDoesNotRescueANeedLoginAccount_thatIsWhyTheButtonsMustNotUseIt() {
        AccountEntity a = in(AccountStatus.NEED_LOGIN);
        a.releaseOk();
        assertEquals(AccountStatus.NEED_LOGIN, a.getStatus());
    }
}
