package com.aifilm.automation.account;

import com.aifilm.automation.project.Provider;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider = Provider.FLOW_BROWSER;

    /** FLOW_BROWSER: thư mục profile Edge (cookie nằm ở đây, không lưu vào DB). */
    @Column(length = 1000)
    private String profileDir;

    /** VERTEX_API: đường dẫn file service-account JSON (không lưu nội dung). */
    @Column(length = 1000)
    private String credentialFile;

    private String gcpProject;
    private String gcpLocation;
    /** SEEDANCE_API / GOOGLE_AI_STUDIO: API key. Không trả ra ngoài qua API (chỉ ghi), giao diện chỉ thấy hasApiKey. */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    @Column(length = 500)
    private String apiKey;

    /** SEEDANCE_API / GOOGLE_AI_STUDIO: mã model video / ảnh; trống = mặc định của engine. */
    private String videoModel;
    private String imageModel;
    private String proxy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status = AccountStatus.NEED_LOGIN;

    private int priority = 100;

    /**
     * Số lệnh Flow chạy cùng lúc trên tài khoản này (gửi lệnh mới khi lệnh trước còn đang tạo). null = 1: chạy lần lượt như cũ.
     * Dùng kiểu Integer (cho phép null) để cột mới thêm vào bảng đã có dữ liệu không cần giá trị mặc định.
     */
    private Integer maxConcurrent;
    private LocalDateTime cooldownUntil;
    private Long leasedJobId;
    private int consecutiveFailures;

    @Column(length = 2000)
    private String lastErrorMessage;

    @Column(length = 1000)
    private String notes;

    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private int version;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @com.fasterxml.jackson.annotation.JsonProperty("hasApiKey")
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public void lease(long jobId, LocalDateTime now) {
        status = AccountStatus.LEASED;
        leasedJobId = jobId;
        lastUsedAt = now;
        cooldownUntil = null;
    }

    /** Số lệnh chạy cùng lúc thực sự dùng: ít nhất 1, nhiều nhất 5. */
    public int effectiveMaxConcurrent() {
        return maxConcurrent == null ? 1 : Math.max(1, Math.min(5, maxConcurrent));
    }

    public void releaseOk() {
        releaseOk(false);
    }

    /**
     * Đưa tài khoản về sẵn sàng bằng quyết định của NGƯỜI DÙNG hoặc của lần kiểm tra đăng nhập ("Đã đăng nhập xong", "Bật lại",
     * "Kiểm tra đăng nhập"). Khác releaseOk: không phụ thuộc trạng thái hiện tại (releaseOk chỉ nhả tài khoản đang chạy job,
     * nên dùng nó cho các nút này thì tài khoản "Cần đăng nhập" / "Tạm nghỉ" / "Đã tắt" không bao giờ đổi trạng thái).
     */
    public void markReady() {
        status = AccountStatus.READY;
        leasedJobId = null;
        consecutiveFailures = 0;
        cooldownUntil = null;
        lastErrorMessage = null;
    }

    /**
     * Một job của tài khoản vừa xong. stillBusy = tài khoản còn job khác đang chạy (chạy song song): giữ nguyên LEASED,
     * chỉ khi job cuối xong mới về READY. Tài khoản đã bị đưa vào nghỉ / cần đăng nhập / tắt thì không bị lật về READY.
     */
    public void releaseOk(boolean stillBusy) {
        consecutiveFailures = 0;
        if (status == AccountStatus.LEASED && !stillBusy) {
            status = AccountStatus.READY;
            leasedJobId = null;
        }
    }

    public void releaseAfterError(String msg) {
        releaseAfterError(msg, false);
    }

    /** Lỗi tạm thời: 3 lần liên tiếp thì cho nghỉ 15 phút để không đốt hết job vào một tài khoản hỏng. */
    public void releaseAfterError(String msg, boolean stillBusy) {
        lastErrorMessage = msg;
        if (++consecutiveFailures >= 3) {
            status = AccountStatus.COOLDOWN;
            cooldownUntil = LocalDateTime.now().plusMinutes(15);
            consecutiveFailures = 0;
            leasedJobId = null;
        } else if (status == AccountStatus.LEASED && !stillBusy) {
            status = AccountStatus.READY;
            leasedJobId = null;
        }
    }

    public void cooldown(LocalDateTime until, String msg) {
        status = AccountStatus.COOLDOWN;
        cooldownUntil = until;
        leasedJobId = null;
        lastErrorMessage = msg;
    }

    public void needLogin(String msg) {
        status = AccountStatus.NEED_LOGIN;
        leasedJobId = null;
        lastErrorMessage = msg;
    }
}
