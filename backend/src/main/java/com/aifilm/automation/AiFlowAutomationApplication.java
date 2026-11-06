package com.aifilm.automation;

import com.aifilm.automation.system.AppPaths;
import com.aifilm.automation.system.Browser;
import com.aifilm.automation.system.DesktopWindow;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

@SpringBootApplication
@EnableScheduling
public class AiFlowAutomationApplication {

    /** Cổng của app; đổi bằng -Dafm.port=... (ví dụ chạy thử song song với một bản đang chạy). */
    public static final int PORT = Integer.getInteger("afm.port", 8082);

    // Giữ tham chiếu suốt đời tiến trình: khoá tự nhả khi JVM thoát, kể cả khi bị tắt đột ngột
    private static FileChannel lockChannel;
    private static FileLock instanceLock;

    public static void main(String[] args) throws IOException {
        AppPaths.init();
        System.setProperty("server.port", String.valueOf(PORT));
        if (isRunning(PORT)) {
            reuseRunningInstance();
            return;
        }
        // Khoá "một bản duy nhất": bấm mở hai lần liên tiếp (ví dụ ô "Mở Flow Batch Studio" cuối bộ cài rồi bấm icon Desktop)
        // thì cả hai đều thấy cổng còn trống và cùng khởi động; bản thứ hai lỗi "Port 8082 was already in use" và thoát với mã
        // khác 0, launcher của jpackage hiện hộp thoại "Failed to launch JVM". Bản nào không lấy được khoá thì chờ bản kia
        // lên rồi chỉ mở lại cửa sổ.
        if (!acquireInstanceLock()) {
            waitUntilRunning(PORT, 90_000);
            reuseRunningInstance();
            return;
        }
        try {
            SpringApplication.run(AiFlowAutomationApplication.class, args);
        } catch (RuntimeException e) {
            // Cổng bị bản khác (khác thư mục dữ liệu) chiếm: không thoát lỗi, mở lại cửa sổ của bản đang chạy
            if (hasCause(e, PortInUseException.class) && isRunning(PORT)) {
                reuseRunningInstance();
                return;
            }
            throw e;
        }
    }

    /** App đã chạy sẵn: chỉ mở lại cửa sổ (Edge đưa cửa sổ của cùng profile lên trước). Chạy dev (noBrowser) thì báo lỗi. */
    private static void reuseRunningInstance() {
        if (Boolean.getBoolean("afm.noBrowser")) {
            System.err.println("Cổng " + PORT + " đang được một bản Flow Batch Studio khác dùng. Tắt bản đó hoặc chạy với -Dafm.port=<cổng khác>.");
            System.exit(1);
        }
        String url = "http://127.0.0.1:" + PORT;
        var edge = DesktopWindow.findAppBrowser();
        if (edge.isPresent()) {
            try {
                DesktopWindow.launch(edge.get(), url);
            } catch (IOException e) {
                Browser.open(url);
            }
        } else {
            Browser.open(url);
        }
    }

    private static boolean acquireInstanceLock() {
        try {
            Files.createDirectories(AppPaths.data());   // máy mới cài: thư mục dữ liệu có thể chưa có
            lockChannel = FileChannel.open(AppPaths.data().resolve("app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            instanceLock = lockChannel.tryLock();
            return instanceLock != null;
        } catch (IOException | RuntimeException e) {
            return true;   // không khoá được (ổ đĩa lạ…): cứ chạy như trước, không chặn người dùng
        }
    }

    private static void waitUntilRunning(int port, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end && !isRunning(port)) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (int i = 0; t != null && i < 20; i++, t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRunning(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
