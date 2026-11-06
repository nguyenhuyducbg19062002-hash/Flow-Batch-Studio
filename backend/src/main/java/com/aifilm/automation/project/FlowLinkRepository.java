package com.aifilm.automation.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FlowLinkRepository extends JpaRepository<FlowLinkEntity, Long> {

    Optional<FlowLinkEntity> findByProjectIdAndAccountId(Long projectId, Long accountId);

    @Modifying
    @Query("delete from FlowLinkEntity l where l.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);

    /** Link hợp lệ: một dự án cụ thể trên Flow, không phải trang chủ. */
    static boolean isProjectUrl(String url) {
        return url != null && url.startsWith("https://flow.google.com/") && url.contains("/project/");
    }
}
