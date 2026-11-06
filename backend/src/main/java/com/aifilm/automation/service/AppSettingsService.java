package com.aifilm.automation.service;

import com.aifilm.automation.model.entity.AppSettingsEntity;
import com.aifilm.automation.repository.AppSettingsRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TreeMap;

@Service
public class AppSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AppSettingsService.class);

    private final AppSettingsRepository settingsRepository;

    public AppSettingsService(AppSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    @PostConstruct
    public void initDefaultSettings() {
        initSettingIfAbsent("worker_processes", "3", "Số tiến trình Python chạy song song (mỗi tiến trình một trình duyệt)");
        initSettingIfAbsent("job_timeout_minutes", "15", "Thời gian tối đa chờ một ảnh/video render xong (phút)");
        initSettingIfAbsent("quota_cooldown_minutes", "360", "Tài khoản hết quota sẽ nghỉ bao lâu trước khi được dùng lại (phút)");
        initSettingIfAbsent("headless", "false", "Chạy trình duyệt ẩn ở chế độ BACKGROUND (Flow có thể chặn chế độ ẩn)");
        initSettingIfAbsent("staging_retention_days", "14", "Giữ file xem trước cũ trong staging bao nhiêu ngày");
    }

    private void initSettingIfAbsent(String key, String defaultValue, String description) {
        if (settingsRepository.findBySettingKey(key).isEmpty()) {
            settingsRepository.save(AppSettingsEntity.builder()
                    .settingKey(key)
                    .settingValue(defaultValue)
                    .description(description)
                    .build());
            log.info("Initialized default app setting: {} = {}", key, defaultValue);
        }
    }

    public Map<String, String> getAllSettings() {
        Map<String, String> map = new TreeMap<>();
        for (AppSettingsEntity e : settingsRepository.findAll()) {
            map.put(e.getSettingKey(), e.getSettingValue());
        }
        return map;
    }

    public String getSetting(String key, String defaultValue) {
        return settingsRepository.findBySettingKey(key)
                .map(AppSettingsEntity::getSettingValue)
                .orElse(defaultValue);
    }

    public int getIntSetting(String key, int defaultValue) {
        try {
            return Integer.parseInt(getSetting(key, String.valueOf(defaultValue)).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public void updateSettings(Map<String, String> settings) {
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            AppSettingsEntity entity = settingsRepository.findBySettingKey(entry.getKey())
                    .orElse(AppSettingsEntity.builder().settingKey(entry.getKey()).build());
            entity.setSettingValue(entry.getValue());
            settingsRepository.save(entity);
        }
    }
}
