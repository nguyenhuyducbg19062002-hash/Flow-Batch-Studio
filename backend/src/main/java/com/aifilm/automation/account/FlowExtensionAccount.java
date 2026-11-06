package com.aifilm.automation.account;

import com.aifilm.automation.project.Provider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * FLOW_EXTENSION không cần người dùng thêm tài khoản: Google nào đang đăng nhập trong tab Flow của Chrome thì dùng tài khoản đó.
 * App vẫn giữ một bản ghi tài khoản nội bộ để hàng đợi chạy như các nền tảng khác (một lệnh mỗi lượt, nghỉ khi Flow
 * báo nghi ngờ / hết quota). Bản ghi được tạo tự động lúc khởi động và khi cần; người dùng có xoá thì lần sau tạo lại.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FlowExtensionAccount {

    public static final String EMAIL = "Chrome (Flow Extension)";

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
}
