package com.aifilm.automation.prompt;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PromptRepository extends JpaRepository<PromptEntity, Long> {

    Optional<PromptEntity> findByProjectIdAndKindAndExternalId(Long projectId, PromptKind kind, String externalId);

    List<PromptEntity> findByProjectId(Long projectId);

    @Modifying
    @Query("delete from PromptEntity p where p.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
