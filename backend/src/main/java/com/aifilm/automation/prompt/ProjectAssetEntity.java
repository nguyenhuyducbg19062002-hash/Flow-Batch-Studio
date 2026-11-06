package com.aifilm.automation.prompt;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Kho ảnh của một phim: mỗi ID (CHAR-CALEB-1, LOC-1, PANEL-12, …) trỏ tới một file ảnh thật trên đĩa.
 * Nguồn: ảnh do app tạo rồi được duyệt (APPROVED), hoặc ảnh người dùng đưa vào (USER: "Thêm ảnh có sẵn", nút "+").
 * Một cảnh (ảnh panel hay video) chỉ được chạy khi MỌI Reference ID của nó đều có trong kho.
 */
@Entity
@Table(name = "project_assets", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "external_id"}))
@Getter
@Setter
@NoArgsConstructor
public class ProjectAssetEntity {

    public static final String SOURCE_APPROVED = "APPROVED";
    public static final String SOURCE_USER = "USER";
    /** Ảnh vừa tạo, đang nằm trong thư mục chờ duyệt (001.png…): dùng được làm ảnh gốc cho ảnh đổi đồ, CHƯA dùng cho video. */
    public static final String SOURCE_REVIEW = "REVIEW";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 100)
    private String externalId;

    @Column(nullable = false, length = 1000)
    private String filePath;

    @Column(nullable = false, length = 10)
    private String source;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ProjectAssetEntity(Long projectId, String externalId, String filePath, String source) {
        this.projectId = projectId;
        this.externalId = externalId;
        this.filePath = filePath;
        this.source = source;
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
