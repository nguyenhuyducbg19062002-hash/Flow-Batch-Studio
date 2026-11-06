package com.aifilm.automation.prompt;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "prompt_revisions", uniqueConstraints = @UniqueConstraint(columnNames = {"prompt_id", "revision_no"}))
@Getter
@Setter
@NoArgsConstructor
public class PromptRevisionEntity {

    public static final String SOURCE_IMPORT = "IMPORT";
    public static final String SOURCE_USER = "USER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long promptId;

    private int revisionNo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String promptText;

    /** IMPORT | USER */
    @Column(nullable = false, length = 10)
    private String source;

    @Column(length = 2000)
    private String note;

    private LocalDateTime createdAt;

    public PromptRevisionEntity(Long promptId, int revisionNo, String promptText, String source, String note) {
        this.promptId = promptId;
        this.revisionNo = revisionNo;
        this.promptText = promptText;
        this.source = source;
        this.note = note;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
