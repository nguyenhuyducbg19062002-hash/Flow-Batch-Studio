package com.aifilm.automation.worker;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

/** Mã bí mật sinh mới mỗi lần chạy, chỉ truyền cho worker Python qua biến môi trường. */
@Component
public class WorkerToken {

    private final String value;

    public WorkerToken() {
        String fixed = System.getProperty("afm.workerToken");   // chỉ dùng khi chạy worker tay lúc phát triển
        if (fixed != null && !fixed.isBlank()) {
            value = fixed;
        } else {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            value = HexFormat.of().formatHex(b);
        }
    }

    public String value() {
        return value;
    }
}
