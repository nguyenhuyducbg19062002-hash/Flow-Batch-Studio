package com.aifilm.automation.job;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Nhật ký chuyển trạng thái: lịch sử tạo lại, xoay tài khoản, ai duyệt lúc nào. */
@Entity
@Table(name = "job_events", indexes = @Index(columnList = "job_id"))
@Getter
@NoArgsConstructor
public class JobEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long jobId;

    @Enumerated(EnumType.STRING)
    private JobStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus toStatus;

    private Long accountId;

    @Column(length = 2000)
    private String message;

    private LocalDateTime createdAt;

    public JobEventEntity(Long jobId, JobStatus fromStatus, JobStatus toStatus, Long accountId, String message) {
        this.jobId = jobId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.accountId = accountId;
        this.message = message == null ? null : message.substring(0, Math.min(message.length(), 2000));
        this.createdAt = LocalDateTime.now();
    }
}
