package com.aifilm.automation.project;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Dự án trên Google Flow mà một tài khoản dùng cho một phim. Dự án Flow thuộc về từng tài khoản Google
 * (tài khoản khác không mở được), nên mỗi cặp (phim, tài khoản) có link riêng. Ảnh reference đã tải lên dự án
 * đó được dùng lại cho các cảnh sau, không phải tải lại.
 */
@Entity
@Table(name = "flow_project_links", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "account_id"}))
@Getter
@Setter
@NoArgsConstructor
public class FlowLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 500)
    private String url;

    private LocalDateTime updatedAt;

    public FlowLinkEntity(Long projectId, Long accountId, String url) {
        this.projectId = projectId;
        this.accountId = accountId;
        this.url = url;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
