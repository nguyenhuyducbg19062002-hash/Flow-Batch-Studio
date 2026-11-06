package com.aifilm.automation.prompt;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PromptRevisionRepository extends JpaRepository<PromptRevisionEntity, Long> {

    @Query("select coalesce(max(r.revisionNo), 0) from PromptRevisionEntity r where r.promptId = :promptId")
    int maxRevisionNo(@Param("promptId") Long promptId);

    List<PromptRevisionEntity> findByPromptIdOrderByRevisionNoDesc(Long promptId);

    /** Bản import gần nhất: import lại chỉ so với bản này, để không ghi đè chỗ người dùng đã sửa tay. */
    Optional<PromptRevisionEntity> findFirstByPromptIdAndSourceOrderByRevisionNoDesc(Long promptId, String source);

    @Modifying
    @Query("delete from PromptRevisionEntity r where r.promptId in (select p.id from PromptEntity p where p.projectId = :projectId)")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
