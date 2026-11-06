package com.aifilm.automation.repository;

import com.aifilm.automation.model.entity.AppSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AppSettingsRepository extends JpaRepository<AppSettingsEntity, Long> {
    Optional<AppSettingsEntity> findBySettingKey(String settingKey);
}
