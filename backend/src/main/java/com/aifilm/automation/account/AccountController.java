package com.aifilm.automation.account;

import com.aifilm.automation.job.JobRepository;
import com.aifilm.automation.job.JobStatus;
import com.aifilm.automation.project.Provider;
import com.aifilm.automation.system.AppPaths;
import com.aifilm.automation.system.DesktopWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Quản lý tài khoản xoay vòng. Không lưu mật khẩu: với Flow, cookie nằm trong profile trình duyệt riêng của từng tài khoản,
 * người dùng tự đăng nhập một lần qua nút "Đăng nhập".
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    static final String FLOW_URL = "https://flow.google.com/";

    private final AccountRepository accounts;
    private final AccountLoginService login;
    private final JobRepository jobs;

    public record AccountForm(String email, Provider provider, String proxy, Integer priority, String notes,
                              String credentialFile, String gcpProject, String gcpLocation, Integer maxConcurrent,
                              String apiKey, String videoModel, String imageModel) {}

    @GetMapping
    public List<AccountEntity> list() {
        return accounts.findAllByOrderByPriorityAscIdAsc();
    }

    @PostMapping
    @Transactional
    public AccountEntity create(@RequestBody AccountForm f) throws IOException {
        if (f.email() == null || f.email().isBlank()) {
            throw new IllegalArgumentException("Email không được để trống");
        }
        String email = f.email().trim();
        if (accounts.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email " + email + " đã có trong danh sách");
        }
        AccountEntity a = new AccountEntity();
        a.setEmail(email);
        a.setProvider(f.provider() == null ? Provider.FLOW_BROWSER : f.provider());
        applyForm(a, f);
        // FLOW_BROWSER phải đăng nhập trước; VERTEX_API / FAKE dùng được ngay
        a.setStatus(a.getProvider() == Provider.FLOW_BROWSER ? AccountStatus.NEED_LOGIN : AccountStatus.READY);
        accounts.save(a);
        Path profile = AppPaths.profiles().resolve("acc_" + a.getId());
        retireProfile(profile);   // thư mục cùng tên còn sót của tài khoản đã xóa ở bản cũ
        Files.createDirectories(profile);
        a.setProfileDir(profile.toString());
        return a;
    }

    @PutMapping("/{id}")
    @Transactional
    public AccountEntity update(@PathVariable long id, @RequestBody AccountForm f) {
        AccountEntity a = accounts.findById(id).orElseThrow();
        applyForm(a, f);
        return a;
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable long id) {
        AccountEntity a = accounts.findById(id).orElseThrow();
        if (a.getStatus() == AccountStatus.LEASED) {
            throw new IllegalStateException("Tài khoản đang chạy job, chờ job xong rồi xóa");
        }
        if (jobs.existsByAccountIdAndStatusIn(id, List.of(JobStatus.GENERATING, JobStatus.DOWNLOADING))) {
            throw new IllegalStateException("Tài khoản còn cảnh đang tạo / đang lưu, chờ xong rồi xóa");
        }
        // profile được đổi tên chứ không xóa (không mất cookie nếu xóa nhầm); tài khoản mới trùng id không thừa hưởng cookie
        retireProfile(AppPaths.profiles().resolve("acc_" + id));
        accounts.delete(a);
    }

    static void retireProfile(Path profile) {
        if (!Files.isDirectory(profile)) {
            return;
        }
        Path to = profile.resolveSibling(profile.getFileName() + "_deleted_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
        try {
            Files.move(profile, to);
        } catch (IOException e) {
            throw new IllegalStateException("Không đổi tên được thư mục profile của tài khoản: đóng cửa sổ Chrome của tài khoản rồi thử lại");
        }
    }

    /** Mở Chrome (không có thì Edge) THƯỜNG (không phải Playwright) với profile của tài khoản để người dùng tự đăng nhập Google. */
    @PostMapping("/{id}/open-login")
    @Transactional
    public AccountEntity openLogin(@PathVariable long id) throws IOException {
        AccountEntity a = accounts.findById(id).orElseThrow();
        if (a.getProvider() != Provider.FLOW_BROWSER) {
            throw new IllegalStateException("Chỉ tài khoản FLOW_BROWSER cần đăng nhập trên trình duyệt");
        }
        if (a.getStatus() == AccountStatus.LEASED) {
            throw new IllegalStateException("Tài khoản đang chạy job, chờ job xong rồi đăng nhập");
        }
        Path browser = DesktopWindow.findAccountBrowser()
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy Google Chrome hay Microsoft Edge trên máy"));
        a.needLogin("đang đăng nhập");   // không READY ⇒ không bị giao job trong lúc profile đang mở
        Files.createDirectories(Path.of(a.getProfileDir()));
        // Mở thẳng trang Google Flow; người dùng bấm "Sign in" trên Flow và tự đăng nhập.
        // (Không dùng accounts.google.com/AccountChooser?Email=…: Google trả lỗi 400 Bad Request cho link này.)
        // --disable-background-mode: đóng cửa sổ là trình duyệt thoát hẳn, nhả profile (không thì nó còn chạy nền giữ khóa profile,
        // job và nút Kiểm tra đăng nhập sau đó báo "profile đang mở ở cửa sổ khác")
        List<String> cmd = new ArrayList<>(List.of(
                browser.toString(),
                "--user-data-dir=" + a.getProfileDir(),
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-mode",
                "--disable-blink-features=AutomationControlled"
        ));
        Path ext = AppPaths.home().resolve("engine").resolve("extension");
        // Chrome bản thường bỏ qua --load-extension (worker nạp extension qua CDP khi chạy job); cửa sổ đăng nhập do người dùng
        // tự thao tác nên không cần extension.
        if (!DesktopWindow.isChrome(browser) && Files.isDirectory(ext) && Files.isRegularFile(ext.resolve("manifest.json"))) {
            cmd.add("--disable-extensions-except=" + ext.toAbsolutePath());
            cmd.add("--load-extension=" + ext.toAbsolutePath());
        }
        cmd.add(FLOW_URL);
        new ProcessBuilder(cmd).start();
        return a;
    }

    /**
     * "Kiểm tra đăng nhập" (nút Kiểm tra lại): tự mở Chrome ẩn với profile của tài khoản, xem đã đăng nhập chưa rồi cập nhật trạng thái
     * (Cần đăng nhập → Sẵn sàng khi đã đăng nhập đúng tài khoản). Không @Transactional: xem AccountLoginService.check.
     */
    @PostMapping("/{id}/check-login")
    public AccountLoginService.CheckResponse checkLogin(@PathVariable long id) {
        return login.check(id);
    }

    /** Người dùng xác nhận đã đăng nhập xong và đã ĐÓNG cửa sổ Chrome của tài khoản. */
    @PostMapping("/{id}/confirm-login")
    @Transactional
    public AccountEntity confirmLogin(@PathVariable long id) {
        AccountEntity a = accounts.findById(id).orElseThrow();
        if (a.getStatus() == AccountStatus.LEASED) {
            throw new IllegalStateException("Tài khoản đang chạy job, chờ job xong rồi xác nhận");
        }
        a.markReady();
        return a;
    }

    @PostMapping("/{id}/enable")
    @Transactional
    public AccountEntity enable(@PathVariable long id) {
        AccountEntity a = accounts.findById(id).orElseThrow();
        if (a.getStatus() == AccountStatus.NEED_LOGIN) {
            throw new IllegalStateException("Tài khoản cần đăng nhập: bấm \"Đăng nhập\" rồi xác nhận đã đăng nhập");
        }
        if (a.getStatus() != AccountStatus.LEASED) {
            a.markReady();
        }
        return a;
    }

    @PostMapping("/{id}/disable")
    @Transactional
    public AccountEntity disable(@PathVariable long id) {
        AccountEntity a = accounts.findById(id).orElseThrow();
        if (a.getStatus() == AccountStatus.LEASED) {
            throw new IllegalStateException("Tài khoản đang chạy job, chờ job xong rồi tắt");
        }
        a.setStatus(AccountStatus.DISABLED);
        return a;
    }

    @GetMapping("/summary")
    public Map<String, Long> summary() {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        for (AccountStatus s : AccountStatus.values()) {
            m.put(s.name(), 0L);
        }
        accounts.findAll().forEach(a -> m.merge(a.getStatus().name(), 1L, Long::sum));
        return m;
    }

    private static void applyForm(AccountEntity a, AccountForm f) {
        a.setProxy(validateProxy(blankToNull(f.proxy())));
        a.setNotes(blankToNull(f.notes()));
        a.setCredentialFile(blankToNull(f.credentialFile()));
        a.setGcpProject(blankToNull(f.gcpProject()));
        a.setGcpLocation(blankToNull(f.gcpLocation()));
        if (f.apiKey() != null && !f.apiKey().isBlank()) {   // để trống khi sửa = giữ key cũ
            a.setApiKey(f.apiKey().trim());
        }
        a.setVideoModel(blankToNull(f.videoModel()));
        a.setImageModel(blankToNull(f.imageModel()));
        if (f.priority() != null) {
            a.setPriority(f.priority());
        }
        if (f.maxConcurrent() != null) {
            if (f.maxConcurrent() < 1 || f.maxConcurrent() > 5) {
                throw new IllegalArgumentException("Số lệnh chạy cùng lúc phải từ 1 đến 5");
            }
            a.setMaxConcurrent(f.maxConcurrent());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** ip:port | ip:port:user:pass | http(s)/socks5://[user:pass@]host:port */
    private static final java.util.regex.Pattern PROXY_PLAIN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9.-]+:\\d{1,5}(:[^:\\s]+:[^:\\s]+)?$");
    private static final java.util.regex.Pattern PROXY_URL =
            java.util.regex.Pattern.compile("^(https?|socks5)://([^:@\\s/]+(:[^@\\s/]*)?@)?[A-Za-z0-9.-]+:\\d{1,5}/?$");

    /** Proxy sai dạng làm mọi lần mở trình duyệt thất bại (ERR_PROXY_CONNECTION_FAILED): chặn ngay khi lưu. */
    static String validateProxy(String proxy) {
        if (proxy == null) {
            return null;
        }
        boolean url = PROXY_URL.matcher(proxy).matches();
        if (!url && !PROXY_PLAIN.matcher(proxy).matches()) {
            // không nhắc lại giá trị proxy: có thể chứa mật khẩu
            throw new IllegalArgumentException("Proxy không đúng dạng. Dùng ip:port, ip:port:user:pass hoặc http://user:pass@ip:port. Không dùng proxy thì để trống.");
        }
        // host:port nằm sau "@" cuối cùng (dạng URL) hoặc ở đầu chuỗi (dạng ip:port:user:pass)
        String hostPort = url ? proxy.substring(Math.max(proxy.indexOf("://") + 3, proxy.lastIndexOf('@') + 1)) : proxy;
        int port = Integer.parseInt(hostPort.split("[:/]")[1]);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Cổng proxy " + port + " không hợp lệ (1–65535)");
        }
        return proxy;
    }
}
