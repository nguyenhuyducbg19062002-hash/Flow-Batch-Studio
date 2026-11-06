package com.aifilm.automation.system;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Tắt hẳn ứng dụng (Spring + worker Python + Edge con) trên một luồng riêng, để kịp trả response cho người gọi. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppShutdown {

    private final ApplicationContext context;
    // ObjectProvider: DesktopWindow cần AppShutdown (để tắt khi cửa sổ đóng), nên AppShutdown lấy DesktopWindow muộn để khỏi vòng phụ thuộc
    private final ObjectProvider<DesktopWindow> window;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public void exitSoon(String reason) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        log.info("Tắt ứng dụng: {}", reason);
        // đóng cửa sổ ngay, cùng lúc với việc tắt app (không đợi Tomcat dừng)
        window.ifAvailable(DesktopWindow::closeWindowAsync);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(150);   // đủ để trả response cho nút Thoát
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(SpringApplication.exit(context, () -> 0));
        }, "shutdown");
        t.setDaemon(false);
        t.start();
    }
}
