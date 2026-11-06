package com.aifilm.automation.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface JobEventRepository extends JpaRepository<JobEventEntity, Long> {

    List<JobEventEntity> findByJobIdOrderByIdDesc(Long jobId);

    @Modifying
    @Query("delete from JobEventEntity e where e.jobId in (select j.id from JobEntity j where j.projectId = :projectId)")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
