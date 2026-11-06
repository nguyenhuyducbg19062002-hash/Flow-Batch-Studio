package com.aifilm.automation.project;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
public class ProjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Khóa phim. Không dùng tên file xlsx: mọi phim của Omni-Flash-Pipeline đều tên all-video-prompts.xlsx. */
    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    /** output/<phim>/ của Omni-Flash-Pipeline. */
    @Column(nullable = false, length = 1000)
    private String sourceDir;

    /** Folder Ảnh do người dùng chọn. */
    @Column(nullable = false, length = 1000)
    private String imageDir;

    /** Folder Video do người dùng chọn. */
    @Column(nullable = false, length = 1000)
    private String videoDir;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunMode runMode = RunMode.BACKGROUND;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider = Provider.FLOW_BROWSER;

    @Column(length = 1000)
    private String flowProjectUrl;

    private String aspectRatio = "16:9";
    private int maxParallel = 3;
    private int maxAttempts = 3;

    /**
     * true: duyệt xong TOÀN BỘ ảnh rồi mới bắt đầu tạo video (đúng quy trình "check ảnh xong → tải hết → chuyển sang video").
     * false: cảnh nào đủ ảnh đã duyệt thì tạo video cảnh đó ngay.
     * Kiểu Boolean (cho phép null) để thêm cột vào DB cũ không lỗi; null được hiểu là true.
     */
    private Boolean waitAllImages = Boolean.TRUE;

    /**
     * true: "Video dùng ảnh có sẵn, không tạo thêm ảnh": app KHÔNG tự tạo ảnh (panel…) nữa, trừ ảnh người dùng
     * bấm chạy riêng; video chạy ngay với những ảnh reference đã có trong kho, ảnh còn thiếu thì bỏ qua.
     */
    private Boolean skipMissingRefs;

    /**
     * true: ảnh tạo xong được duyệt tự động (vào kho ảnh ngay) để video chạy liền, không đợi người duyệt.
     * Mặc định tắt: ảnh xấu thì video cũng xấu, nên người dùng tự quyết định bật.
     */
    private Boolean autoApproveImages;

    public boolean isAutoApproveImagesOn() {
        return Boolean.TRUE.equals(autoApproveImages);
    }

    /** Đã bỏ chế độ này: video BẮT BUỘC có đủ ảnh tham chiếu mới được tạo. Luôn false (kể cả phim cũ còn cờ trong DB). */
    public boolean isSkipMissingRefsOn() {
        return false;
    }

    public Boolean getSkipMissingRefs() {
        return false;
    }

    /** true: chỉ chạy các cảnh người dùng chọn (run_priority > 0); chạy hết thì phim tự tạm dừng. */
    private Boolean selectedOnly;

    public boolean isSelectedOnlyOn() {
        return Boolean.TRUE.equals(selectedOnly);
    }

    /** Độ dài video (giây) chọn trên nền tảng trước khi tạo; null = theo prompt / mặc định của nền tảng. */
    private Integer videoDuration;

    /** Chất lượng khi tải video về: 480p | 720p | 1080p. null = 720p. */
    private String videoQuality;

    /** Seedance: tạo kèm âm thanh (Playground "sound"). null = bật. */
    private Boolean generateAudio;

    /** Phần "Tạo ảnh" đang chạy (tách riêng với phần video). null = tắt. */
    private Boolean runImages;

    /** Phần "Tạo video" đang chạy. null = tắt. */
    private Boolean runVideos;

    /** Thư mục chờ duyệt ảnh: kết quả tải về đặt tên theo số thứ tự (001.png…). null = "<cha của Folder Ảnh>/Ảnh chờ duyệt". */
    @Column(length = 1000)
    private String imageReviewDir;

    /** Thư mục chờ duyệt video (001.mp4…). null = "<cha của Folder Video>/Video chờ duyệt". */
    @Column(length = 1000)
    private String videoReviewDir;

    public boolean isRunning(com.aifilm.automation.prompt.PromptKind kind) {
        return Boolean.TRUE.equals(kind == com.aifilm.automation.prompt.PromptKind.IMAGE ? runImages : runVideos);
    }

    /** Bật / tắt một phần; phim RUNNING khi còn ít nhất một phần chạy. */
    public void setRunning(com.aifilm.automation.prompt.PromptKind kind, boolean on, String reasonWhenStopped) {
        if (kind == com.aifilm.automation.prompt.PromptKind.IMAGE) {
            runImages = on;
        } else {
            runVideos = on;
        }
        if (on) {
            status = ProjectStatus.RUNNING;
            pausedReason = null;
        } else if (!Boolean.TRUE.equals(runImages) && !Boolean.TRUE.equals(runVideos)) {
            pause(reasonWhenStopped);
        }
    }

    /** Thư mục chờ duyệt thực dùng của một phần. */
    public java.nio.file.Path reviewDir(com.aifilm.automation.prompt.PromptKind kind) {
        boolean img = kind == com.aifilm.automation.prompt.PromptKind.IMAGE;
        String set = img ? imageReviewDir : videoReviewDir;
        if (set != null && !set.isBlank()) {
            return java.nio.file.Path.of(set.trim());
        }
        java.nio.file.Path base = java.nio.file.Path.of(img ? imageDir : videoDir).toAbsolutePath();
        java.nio.file.Path parent = base.getParent() == null ? base : base.getParent();
        return parent.resolve(img ? "Ảnh chờ duyệt" : "Video chờ duyệt");
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectStatus status = ProjectStatus.DRAFT;

    @Column(length = 2000)
    private String pausedReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isWaitAllImagesOn() {
        return waitAllImages == null || waitAllImages;
    }

    public void pause(String reason) {
        status = ProjectStatus.PAUSED;
        pausedReason = reason;
    }

    public int effectiveParallel() {
        // mỗi tài khoản một cửa sổ / một cảnh cùng lúc (tài khoản được "thuê" riêng), nên chế độ Mở Flow cũng chạy song song
        return Math.max(1, maxParallel);
    }
}
