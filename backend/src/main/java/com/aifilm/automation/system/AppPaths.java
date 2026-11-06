package com.aifilm.automation.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Thư mục cài đặt (chỉ đọc) và thư mục dữ liệu người dùng (%LOCALAPPDATA%\FlowBatchStudio). */
public final class AppPaths {

    private AppPaths() {}

    /** jpackage đặt sẵn system property jpackage.app-path = đường dẫn file .exe; khi chạy dev thì là thư mục hiện tại. */
    public static Path home() {
        String exe = System.getProperty("jpackage.app-path");
        if (exe != null) {
            return Path.of(exe).toAbsolutePath().getParent();
        }
        String override = System.getProperty("afm.home");
        if (override != null) {
            return Path.of(override).toAbsolutePath();
        }
        // Chạy dev: thư mục làm việc có thể là gốc dự án hoặc backend\ (cấu hình mặc định của IntelliJ).
        // Chọn thư mục nào có engine\worker_main.py.
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("engine").resolve("worker_main.py"))) {
                return p;
            }
        }
        return cwd;
    }

    /** Dữ liệu người dùng: KHÔNG ghi vào thư mục cài đặt (có thể chỉ đọc và bị xóa khi cập nhật). */
    public static Path data() {
        String override = System.getProperty("afm.data.dir");
        if (override != null) {
            return Path.of(override).toAbsolutePath();
        }
        String base = System.getenv("LOCALAPPDATA");
        return base != null ? Path.of(base, "FlowBatchStudio") : Path.of("data").toAbsolutePath();
    }

    public static Path staging() {
        return data().resolve("staging");
    }

    public static Path profiles() {
        return data().resolve("profiles");
    }

    public static Path logs() {
        return data().resolve("logs");
    }

    public static void init() throws IOException {
        for (Path p : List.of(logs(), staging(), profiles())) {
            Files.createDirectories(p);
        }
        // application.yml và logback-spring.xml đọc hai property này
        System.setProperty("afm.data", data().toString().replace('\\', '/'));
        System.setProperty("afm.logs", logs().toString().replace('\\', '/'));
    }
}
