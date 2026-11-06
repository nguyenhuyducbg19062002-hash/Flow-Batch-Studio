package com.aifilm.automation.prompt;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PromptReferenceRepository extends JpaRepository<PromptReferenceEntity, Long> {

    List<PromptReferenceEntity> findByVideoPromptIdOrderByOrdinalAsc(Long videoPromptId);

    List<PromptReferenceEntity> findByVideoPromptIdIn(Collection<Long> videoPromptIds);

    @Modifying
    @Query("delete from PromptReferenceEntity r where r.videoPromptId = :videoPromptId")
    void deleteByVideoPromptId(@Param("videoPromptId") Long videoPromptId);

    /** Import lại chỉ thay các ảnh đến từ Excel; ảnh người dùng thêm bằng "+" được giữ nguyên. */
    @Modifying
    @Query("delete from PromptReferenceEntity r where r.videoPromptId = :promptId and (r.source is null or r.source <> 'USER')")
    void deleteImportedByPromptId(@Param("promptId") Long promptId);

    /** Mọi Reference ID mà các prompt của phim cần (để biết ảnh gốc nào còn thiếu). */
    @Query("select distinct r.refExternalId from PromptReferenceEntity r, PromptEntity p where p.id = r.videoPromptId and p.projectId = :projectId")
    List<String> findDistinctRefIds(@Param("projectId") Long projectId);

    @Modifying
    @Query("delete from PromptReferenceEntity r where r.videoPromptId in (select p.id from PromptEntity p where p.projectId = :projectId)")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
