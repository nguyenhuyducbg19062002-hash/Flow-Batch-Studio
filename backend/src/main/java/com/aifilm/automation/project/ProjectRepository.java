package com.aifilm.automation.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<ProjectEntity, Long> {

    List<ProjectEntity> findByStatusOrderByIdAsc(ProjectStatus status);

    List<ProjectEntity> findAllByOrderByIdDesc();

    Optional<ProjectEntity> findByCode(String code);
}
