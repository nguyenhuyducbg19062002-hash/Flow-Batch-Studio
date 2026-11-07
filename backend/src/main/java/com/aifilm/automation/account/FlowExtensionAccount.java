package com.aifilm.automation.account;

import com.aifilm.automation.project.Provider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * FLOW_EXTENSION không cần người dùng thêm tài khoản: Google nào đang đăng nhập trong tab Flow của Chrome thì dùng tài khoản đó.
 * App vẫn giữ một bản ghi tài khoản nội bộ để hàng đợi chạy như các nền tảng khác (một lệnh mỗi lượt, nghỉ khi Flow
 * báo nghi ngờ / hết quota). Bản ghi được tạo tự động lúc khởi động và khi cần; người dùng có xoá thì lần sau tạo lại.
 *
 * Nhiều tài khoản: mỗi tài khoản Google một hồ sơ Chrome có cài extension. Extension tự báo email đang đăng nhập Flow, worker
 * giữ cầu nối báo danh sách email về (sync, mỗi ~10 giây). App tự thêm tài khoản theo email, bật lại khi Chrome của nó nối
 * lại, tạm nghỉ (NEED_LOGIN) khi Chrome đó tắt; hàng đợi chia cảnh cho các tài khoản đang rảnh như mọi nền tảng khác.
 * Khi đã có Chrome báo email, tài khoản chung tạm nghỉ để cảnh nào cũng chạy trên đúng Chrome của tài khoản được giao.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FlowExtensionAccount {

    public static final String EMAIL = "Chrome (Flow Extension)";
    static final String OFFLINE = "Chrome của tài khoản này chưa mở / chưa bật extension Flow Bridge (mở lại thì tự dùng tiếp)";
    static final String SHARED_PAUSED = "Tạm nghỉ: đã có tài khoản theo email của từng Chrome";

    private final Set<String> warned = new HashSet<>();

    private final AccountRepository accounts;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            ensure();
        } catch (RuntimeException e) {
            log.warn("Không tạo được tài khoản nội bộ cho Flow Extension: {}", e.getMessage());
        }
    }

    /** Có ít nhất một tài khoản FLOW_EXTENSION; chưa có thì tạo. */
    public synchronized void ensure() {
        if (accounts.existsByProvider(Provider.FLOW_EXTENSION)) {
            return;
        }
        AccountEntity a = accounts.findByEmail(EMAIL).orElseGet(AccountEntity::new);
        a.setEmail(EMAIL);
        a.setProvider(Provider.FLOW_EXTENSION);
        a.setStatus(AccountStatus.READY);
        a.setPriority(100);
        a.setNotes("Tự tạo: dùng Google đang đăng nhập trong tab flow.google.com của Chrome (không cần thêm tài khoản)");
        if (a.getCreatedAt() == null) {
            a.setCreatedAt(LocalDateTime.now());
        }
        a.setUpdatedAt(LocalDateTime.now());
        accounts.save(a);
        log.info("Đã tạo tài khoản nội bộ cho Flow Extension");
    }

    /** Email hợp lệ (chữ thường) hoặc null. */
    static String normalize(String email) {
        if (email == null) {
            return null;
        }
        String e = email.trim().toLowerCase(Locale.ROOT);
        return e.matches("[^@\\s]+@[^@\\s]+") ? e : null;
    }

    /**
     * Đồng bộ tài khoản theo các Chrome đang nối app.
     * @param emails    email Google của từng Chrome có extension đang nối
     * @param anonymous có extension chưa đọc được email (bản cũ / chưa mở tab Flow): tài khoản chung vẫn dùng được
     */
    public synchronized void sync(List<String> emails, boolean anonymous) {
        Set<String> online = new LinkedHashSet<>();
        if (emails != null) {
            for (String e : emails) {
                String n = normalize(e);
                if (n != null) {
                    online.add(n);
                }
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (String email : online) {
            try {
                AccountEntity a = accounts.findByEmail(email).orElse(null);
                if (a == null) {
                    a = new AccountEntity();
                    a.setEmail(email);
                    a.setProvider(Provider.FLOW_EXTENSION);
                    a.setStatus(AccountStatus.READY);
                    a.setPriority(100);
                    a.setNotes("Tự thêm: hồ sơ Chrome có extension Flow Bridge đăng nhập tài khoản này");
                    a.setCreatedAt(now);
                    a.setUpdatedAt(now);
                    accounts.save(a);
                    log.info("Flow Extension: tự thêm tài khoản {}", email);
                } else if (a.getProvider() != Provider.FLOW_EXTENSION) {
                    if (warned.add(email)) {
                        log.warn("Flow Extension: {} đã có ở dạng tài khoản {}, không tự thêm bản Flow Extension", email, a.getProvider());
                    }
                } else if (a.getStatus() == AccountStatus.NEED_LOGIN) {
                    a.setStatus(AccountStatus.READY);
                    a.setLastErrorMessage(null);
                    a.setUpdatedAt(now);
                    accounts.save(a);
                    log.info("Flow Extension: Chrome của {} đã nối, dùng lại tài khoản", email);
                }
            } catch (RuntimeException ex) {   // vừa bị job khác cập nhật (khóa phiên bản): lần đồng bộ sau làm tiếp
                log.debug("Đồng bộ tài khoản {} lỗi: {}", email, ex.getMessage());
            }
        }
        List<AccountEntity> all = accounts.findByProvider(Provider.FLOW_EXTENSION);
        // đã có tài khoản theo email (đang dùng nhiều Chrome): tài khoản chung chỉ dùng khi có extension bản cũ chưa rõ email,
        // kể cả lúc chưa Chrome nào nối, để cảnh không rơi vào tài khoản chung rồi tranh Chrome với tài khoản theo email
        boolean perEmail = all.stream().anyMatch(x -> normalize(x.getEmail()) != null && x.getStatus() != AccountStatus.DISABLED);
        for (AccountEntity a : all) {
            try {
                String email = normalize(a.getEmail());
                AccountStatus st = a.getStatus();
                if (email == null) {
                    // tài khoản chung: dùng khi chưa có Chrome nào báo email (hoặc có extension bản cũ)
                    boolean use = anonymous || (online.isEmpty() && !perEmail);
                    if (!use && (st == AccountStatus.READY || st == AccountStatus.COOLDOWN)) {
                        a.setStatus(AccountStatus.NEED_LOGIN);
                        a.setLastErrorMessage(SHARED_PAUSED);
                    } else if (use && st == AccountStatus.NEED_LOGIN && SHARED_PAUSED.equals(a.getLastErrorMessage())) {
                        a.setStatus(AccountStatus.READY);
                        a.setLastErrorMessage(null);
                    } else {
                        continue;
                    }
                } else if (online.contains(email)) {
                    continue;
                } else if (st == AccountStatus.READY || (st == AccountStatus.COOLDOWN && a.getCooldownUntil() != null
                        && !a.getCooldownUntil().isAfter(now))) {
                    a.setStatus(AccountStatus.NEED_LOGIN);   // Chrome của tài khoản này đã tắt: đừng giao cảnh cho nó
                    a.setLastErrorMessage(OFFLINE);
                    log.info("Flow Extension: Chrome của {} đã ngắt, tạm nghỉ tài khoản", email);
                } else {
                    continue;
                }
                a.setUpdatedAt(now);
                accounts.save(a);
            } catch (RuntimeException ex) {
                log.debug("Đồng bộ tài khoản {} lỗi: {}", a.getEmail(), ex.getMessage());
            }
        }
    }
}
