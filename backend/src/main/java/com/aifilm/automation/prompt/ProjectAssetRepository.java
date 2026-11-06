package com.aifilm.automation.prompt;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectAssetRepository extends JpaRepository<ProjectAssetEntity, Long> {

    Optional<ProjectAssetEntity> findByProjectIdAndExternalId(Long projectId, String externalId);

    List<ProjectAssetEntity> findByProjectId(Long projectId);

    @Modifying
    @Query("delete from ProjectAssetEntity a where a.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
