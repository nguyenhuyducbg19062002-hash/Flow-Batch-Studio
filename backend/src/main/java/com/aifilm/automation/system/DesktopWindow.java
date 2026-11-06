package com.aifilm.automation.system;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Mở giao diện như một ứng dụng desktop: cửa sổ Edge ở chế độ --app (không thanh địa chỉ, không tab,
 * biểu tượng riêng trên taskbar), dùng profile Edge riêng của app.
 *
 * Đóng cửa sổ = tắt ứng dụng. Có hai tín hiệu, vì Edge đôi khi vẫn giữ tiến trình sau khi cửa sổ đóng:
 *  1. tiến trình Edge của cửa sổ kết thúc;
 *  2. trang gửi "ui-closed" (sự kiện pagehide).
 * Trước khi tắt luôn đợi vài giây: nếu trang vẫn gửi heartbeat (người dùng chỉ tải lại trang) thì không tắt.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DesktopWindow {

    private static final int WIDTH = 1440;
    private static final int HEIGHT = 920;

    private final AppShutdown shutdown;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ui-window");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean desktopMode;
    private volatile long lastHeartbeat;
    private ScheduledFuture<?> pendingShutdown;

    /** Gọi lúc app khởi động xong. */
    public void openOnStartup(String url) {
        if (Boolean.getBoolean("afm.noBrowser")) {
            return;   // chạy dev (IntelliJ + npm run dev): không mở cửa sổ
        }
        Optional<Path> edge = findEdge();
        if (edge.isEmpty()) {
            log.warn("Không tìm thấy Microsoft Edge: mở giao diện bằng trình duyệt mặc định");
            Browser.open(url);
            return;
        }
        try {
            Process p = launch(edge.get(), url);
            desktopMode = true;
            Thread watcher = new Thread(() -> {
                try {
                    p.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                log.info("Tiến trình cửa sổ ứng dụng đã kết thúc");
                scheduleShutdown(4);
            }, "ui-window-watch");
            watcher.setDaemon(true);
            watcher.start();
        } catch (IOException e) {
            log.warn("Không mở được cửa sổ ứng dụng ({}): dùng trình duyệt mặc định", e.getMessage());
            Browser.open(url);
        }
    }

    /**
     * Đóng cửa sổ app khi ứng dụng tắt (nút "Thoát ứng dụng", Stop trong IntelliJ, tắt từ nơi khác). Trang web không tự đóng
     * được cửa sổ của chính nó, nên trước đây cửa sổ còn nằm lại với dòng "Ứng dụng đã tắt".
     * Profile Edge của app là riêng (ui-window) nên chỉ đóng đúng cửa sổ này, không đụng Edge của người dùng.
     */
    @PreDestroy
    public void closeWindow() {
        closeWindowAsync();
        Process ps = closer;
        if (ps == null) {
            return;
        }
        try {
            if (!ps.waitFor(10, TimeUnit.SECONDS)) {
                ps.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private volatile Process closer;   // lệnh PowerShell đóng cửa sổ (chỉ chạy một lần)

    /**
     * Bắt đầu đóng cửa sổ NGAY và không chờ xong: AppShutdown gọi cái này đầu tiên khi nhận lệnh thoát, để cửa sổ tắt cùng
     * lúc với app thay vì đợi Tomcat dừng rồi mới đóng. Gọi nhiều lần cũng chỉ chạy một lần.
     */
    public synchronized void closeWindowAsync() {
        if (!desktopMode || closer != null) {
            return;
        }
        // Tiến trình Edge mà Java khởi chạy chỉ là bộ khởi động; cửa sổ thật là tiến trình Edge khác, và Java không đọc được
        // dòng lệnh của nó trên Windows (ProcessHandle.info().commandLine() rỗng). Nên hỏi WMI qua PowerShell:
        // mọi tiến trình msedge.exe có đường dẫn profile ui-window trong dòng lệnh là của cửa sổ này.
        String profile = profileDir().toString().toLowerCase(java.util.Locale.ROOT).replace("'", "''");
        // Không dùng dấu ngoặc kép trong lệnh: Windows bỏ chúng khi truyền tham số cho powershell.exe (-Filter thành "Invalid query")
        String script = "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'msedge.exe' -and $_.CommandLine -and "
                + "$_.CommandLine.ToLower().Contains('" + profile + "') } | "
                + "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }";
        try {
            log.info("Đóng cửa sổ ứng dụng");
            closer = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            log.warn("Không đóng được cửa sổ ứng dụng: {}", e.getMessage());
        }
    }

    private static Path profileDir() {
        return AppPaths.data().resolve("ui-window");
    }

    public void heartbeat() {
        lastHeartbeat = System.currentTimeMillis();
    }

    public void uiClosed() {
        if (desktopMode) {
            scheduleShutdown(8);
        }
    }

    private synchronized void scheduleShutdown(int seconds) {
        if (pendingShutdown != null) {
            pendingShutdown.cancel(false);
        }
        long mark = System.currentTimeMillis();
        pendingShutdown = timer.schedule(() -> {
            if (lastHeartbeat > mark) {
                return;   // trang vẫn sống (vừa tải lại): không tắt
            }
            shutdown.exitSoon("cửa sổ ứng dụng đã đóng");
        }, seconds, TimeUnit.SECONDS);
    }

    /** Dùng cả khi bấm đúp lần hai lúc app đã chạy: Edge chuyển yêu cầu sang cửa sổ đang mở của cùng profile. */
    public static Process launch(Path edge, String url) throws IOException {
        Path profile = profileDir();
        Files.createDirectories(profile);
        List<String> cmd = new ArrayList<>(List.of(
                edge.toString(),
                "--app=" + url,
                "--user-data-dir=" + profile,
                "--window-size=" + WIDTH + "," + HEIGHT,
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-mode",
                "--hide-crash-restore-bubble"));
        return new ProcessBuilder(cmd).start();
    }

    public static Optional<Path> findEdge() {
        List<Path> candidates = new ArrayList<>();
        for (String env : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
            String base = System.getenv(env);
            if (base != null) {
                candidates.add(Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe"));
            }
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst();
    }

    public static Optional<Path> findChrome() {
        List<Path> candidates = new ArrayList<>();
        for (String env : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
            String base = System.getenv(env);
            if (base != null) {
                candidates.add(Path.of(base, "Google", "Chrome", "Application", "chrome.exe"));
            }
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst();
    }

    /**
     * Trình duyệt mở profile tài khoản Flow: Chrome nếu máy có, không thì Edge. Phải khớp với engine/drivers/stealth.browser_channel
     * (cùng đọc AVS_BROWSER_CHANNEL), vì profile do trình duyệt nào tạo thì cookie chỉ trình duyệt đó đọc được.
     */
    public static Optional<Path> findAccountBrowser() {
        String ch = Optional.ofNullable(System.getenv("AVS_BROWSER_CHANNEL")).orElse("").trim().toLowerCase();
        if (ch.equals("msedge")) {
            return findEdge();
        }
        return findChrome().or(DesktopWindow::findEdge);
    }

    public static boolean isChrome(Path browser) {
        return browser.getFileName().toString().equalsIgnoreCase("chrome.exe");
    }
}
