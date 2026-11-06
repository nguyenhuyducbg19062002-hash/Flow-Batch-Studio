package com.aifilm.automation.prompt;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "prompts", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "kind", "external_id"}))
@Getter
@Setter
@NoArgsConstructor
public class PromptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PromptKind kind;

    /** IMAGE: CHAR-DOMINIC-1, LOC-1, PROP-2 … ; VIDEO: 001..298. Cũng là tên file cuối cùng. */
    @Column(nullable = false, length = 100)
    private String externalId;

    /** Thứ tự trong file Excel (cột STT, hoặc số dòng nếu file không có cột STT). */
    private int seqNo;

    /** Tên file Excel đã nạp prompt này, và thứ tự của file đó trong phim (file nạp trước đứng trước). */
    private String sourceName;
    private Integer sourceOrder;

    /** Mô tả cảnh tiếng Việt: cột "Ghi chú" hoặc "Tóm tắt segment (tiếng Việt)". */
    @Column(columnDefinition = "TEXT")
    private String summaryVi;

    private Long currentRevisionId;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
