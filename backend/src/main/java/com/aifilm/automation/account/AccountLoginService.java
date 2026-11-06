package com.aifilm.automation.account;

import com.aifilm.automation.project.Provider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** "Kiểm tra đăng nhập": tự mở Chrome ẩn với profile của tài khoản, xem đã đăng nhập chưa rồi cập nhật trạng thái. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountLoginService {

    public record CheckResponse(AccountEntity account, String state, String email, String message) {}

    private final AccountRepository accounts;
    private final LoginChecker checker;
    private final TransactionTemplate tx;
    private final Set<Long> checking = ConcurrentHashMap.newKeySet();   // mỗi profile chỉ mở một trình duyệt một lúc

    /**
     * KHÔNG đặt @Transactional ở đây: lần kiểm tra mất vài giây, mà DB chỉ có một kết nối (SQLite), giữ transaction suốt thời gian
     * đó sẽ chặn mọi thứ khác. Đọc nhanh → kiểm tra (không DB) → ghi nhanh trong transaction riêng.
     */
    public CheckResponse check(long id) {
        AccountEntity a = accounts.findById(id).orElseThrow();
        if (a.getProvider() != Provider.FLOW_BROWSER) {
            throw new IllegalStateException("Chỉ tài khoản Google Flow cần kiểm tra đăng nhập");
        }
        if (a.getStatus() == AccountStatus.LEASED) {
            throw new IllegalStateException("Tài khoản đang chạy job, chờ job xong rồi kiểm tra");
        }
        if (!checking.add(id)) {
            throw new IllegalStateException("Đang kiểm tra tài khoản này, chờ một chút");
        }
        try {
            LoginChecker.Result r = checker.check(a.getProfileDir(), a.getProxy());
            return tx.execute(status -> apply(id, r));
        } finally {
            checking.remove(id);
        }
    }

    private CheckResponse apply(long id, LoginChecker.Result r) {
        AccountEntity cur = accounts.findById(id).orElseThrow();
        if (cur.getStatus() == AccountStatus.LEASED) {   // trong lúc kiểm tra đã có job nhận tài khoản: không đụng
            return new CheckResponse(cur, r.state(), r.email(), "Tài khoản vừa nhận việc, bỏ qua kết quả kiểm tra");
        }
        LoginCheckPolicy.Decision d = LoginCheckPolicy.decide(cur.getStatus(), cur.getEmail(), r);
        if (d.newStatus() == AccountStatus.READY) {
            cur.markReady();
        } else if (d.newStatus() == AccountStatus.NEED_LOGIN) {
            cur.needLogin(d.message());
        }
        log.info("Kiểm tra đăng nhập {}: {} ({}) → {}", cur.getEmail(), r.state(), r.email(), cur.getStatus());
        return new CheckResponse(cur, r.state(), r.email(), d.message());
    }
}
