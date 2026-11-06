package com.aifilm.automation.job;

import com.aifilm.automation.prompt.PromptKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Một job cho mỗi prompt. Tạo lại = đưa chính job đó về PENDING; lịch sử nằm ở job_events. */
@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
public class JobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, unique = true)
    private Long promptId;

    /** Revision đã dùng để sinh kết quả hiện tại. */
    private Long revisionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PromptKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status = JobStatus.PENDING;

    private Long accountId;
    private String workerId;
    private int attempt;
    private boolean cancelRequested;

    /**
     * "Chạy cảnh này": số lớn hơn được giao trước mọi job khác (kể cả khi phim đang chờ duyệt hết ảnh).
     * Kiểu Integer (cho phép null) để thêm cột vào DB cũ không lỗi; null = 0.
     */
    private Integer runPriority;

    @Column(length = 1000)
    private String remoteRef;

    /** Địa chỉ màn chỉnh sửa của kết quả trên Flow (…/project/<id>/edit/<media>) và tài khoản đã tạo nó: dùng cho "Sửa trên Flow". */
    @Column(length = 1000)
    private String flowMediaUrl;
    private Long flowAccountId;

    /** "Sửa trên Flow": yêu cầu sửa gửi vào ô "Mô tả cách chỉnh sửa video này" của chính kết quả cũ. null = tạo mới bình thường. */
    @Column(length = 2000)
    private String editInstruction;

    @Column(length = 1000)
    private String previewPath;

    @Column(length = 1000)
    private String finalPath;

    /** File trong thư mục chờ duyệt (001.png…), null khi chưa tải về / đã đổi tên sang thư mục đích. */
    @Column(length = 1000)
    private String reviewPath;

    /** Số thứ tự của file trong thư mục chờ duyệt (người duyệt báo lại số này nếu sai). */
    private Integer reviewNo;

    private String errorCode;

    @Column(length = 2000)
    private String errorMessage;

    @Column(length = 2000)
    private String reviewNote;

    private LocalDateTime heartbeatAt;
    private LocalDateTime startedAt;
    private LocalDateTime generatedAt;
    private LocalDateTime reviewedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private int version;

    public JobEntity(Long projectId, Long promptId, PromptKind kind) {
        this.projectId = projectId;
        this.promptId = promptId;
        this.kind = kind;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
