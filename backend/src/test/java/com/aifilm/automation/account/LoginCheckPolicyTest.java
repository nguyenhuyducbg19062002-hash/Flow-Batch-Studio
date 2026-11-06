package com.aifilm.automation.account;

import com.aifilm.automation.account.LoginChecker.Result;
import org.junit.jupiter.api.Test;

import static com.aifilm.automation.account.AccountStatus.COOLDOWN;
import static com.aifilm.automation.account.AccountStatus.DISABLED;
import static com.aifilm.automation.account.AccountStatus.NEED_LOGIN;
import static com.aifilm.automation.account.AccountStatus.READY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginCheckPolicyTest {

    private static final String ME = "quantrigepa3@gmail.com";

    private static Result r(String state, String email) {
        return new Result(state, email, "");
    }

    @Test
    void loggedIn_needLoginBecomesReady_caseInsensitiveEmail() {
        assertEquals(READY, LoginCheckPolicy.decide(NEED_LOGIN, ME, r("logged_in", ME)).newStatus());
        assertEquals(READY, LoginCheckPolicy.decide(NEED_LOGIN, ME, r("logged_in", "QuanTriGepa3@Gmail.com")).newStatus());
        assertEquals(READY, LoginCheckPolicy.decide(NEED_LOGIN, ME, r("logged_in", "")).newStatus(), "không đọc được email thì tin dấu hiệu đã đăng nhập");
    }

    @Test
    void loggedIn_readyStaysReady() {
        var d = LoginCheckPolicy.decide(READY, ME, r("logged_in", ME));
        assertNull(d.newStatus());
        assertTrue(d.message().contains("vẫn đang đăng nhập"));
    }

    @Test
    void loggedIn_butWrongAccount_isNotReady() {
        var d = LoginCheckPolicy.decide(NEED_LOGIN, ME, r("logged_in", "nguoi.khac@gmail.com"));
        assertEquals(NEED_LOGIN, d.newStatus());
        assertTrue(d.message().contains("nguoi.khac@gmail.com") && d.message().contains(ME));
        assertEquals(NEED_LOGIN, LoginCheckPolicy.decide(READY, ME, r("logged_in", "nguoi.khac@gmail.com")).newStatus(), "đang READY mà sai tài khoản thì hạ xuống");
    }

    @Test
    void loggedOut_demotesReady_andKeepsNeedLogin() {
        assertEquals(NEED_LOGIN, LoginCheckPolicy.decide(READY, ME, r("logged_out", "")).newStatus());
        assertEquals(NEED_LOGIN, LoginCheckPolicy.decide(NEED_LOGIN, ME, r("logged_out", "")).newStatus());
    }

    @Test
    void cooldownAndDisabledAreNeverTouchedByTheCheck() {
        for (var s : new AccountStatus[]{COOLDOWN, DISABLED}) {
            assertNull(LoginCheckPolicy.decide(s, ME, r("logged_in", ME)).newStatus(), "logged_in từ " + s);
            assertNull(LoginCheckPolicy.decide(s, ME, r("logged_out", "")).newStatus(), "logged_out từ " + s);
            assertNull(LoginCheckPolicy.decide(s, ME, r("logged_in", "x@y.com")).newStatus(), "sai tài khoản từ " + s);
        }
        assertTrue(LoginCheckPolicy.decide(COOLDOWN, ME, r("logged_in", ME)).message().contains("Bật lại"));
    }

    @Test
    void inconclusiveResultsChangeNothing() {
        for (var s : new AccountStatus[]{NEED_LOGIN, READY, COOLDOWN, DISABLED}) {
            assertNull(LoginCheckPolicy.decide(s, ME, r("in_use", "")).newStatus());
            assertNull(LoginCheckPolicy.decide(s, ME, new Result("error", "", "mạng lỗi")).newStatus());
        }
        assertTrue(LoginCheckPolicy.decide(NEED_LOGIN, ME, r("in_use", "")).message().contains("ĐÓNG"));
        assertTrue(LoginCheckPolicy.decide(NEED_LOGIN, ME, new Result("error", "", "mạng lỗi")).message().contains("mạng lỗi"));
    }

    @Test
    void parseTakesTheLastJsonLine_andSurvivesGarbage() {
        String out = "Warning: something\n{\"state\": \"logged_out\", \"email\": \"\", \"message\": \"cũ\"}\nnoise\n"
                + "{\"state\": \"logged_in\", \"email\": \"a@b.com\", \"message\": \"Đã đăng nhập\"}\n";
        assertEquals(new Result("logged_in", "a@b.com", "Đã đăng nhập"), LoginChecker.parse(out));
        assertEquals("error", LoginChecker.parse("Traceback (most recent call last): ...").state());
        assertEquals("error", LoginChecker.parse("").state());
        assertEquals("error", LoginChecker.parse(null).state());
    }
}
