package com.aifilm.automation.system;

import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class Browser {

    private Browser() {}

    /** Mở URL bằng trình duyệt mặc định của Windows. Spring Boot chạy headless nên không dùng java.awt.Desktop. */
    public static void open(String url) {
        if (Boolean.getBoolean("afm.noBrowser")) {
            return;
        }
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (IOException e) {
            LoggerFactory.getLogger(Browser.class).warn("Không mở được trình duyệt, hãy mở tay: {}", url);
        }
    }
}
