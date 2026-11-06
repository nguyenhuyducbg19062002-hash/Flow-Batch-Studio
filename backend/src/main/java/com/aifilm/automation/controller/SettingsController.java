package com.aifilm.automation.controller;

import com.aifilm.automation.service.AppSettingsService;
import com.aifilm.automation.worker.PythonWorkerSupervisor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final AppSettingsService settingsService;
    private final PythonWorkerSupervisor supervisor;

    public SettingsController(AppSettingsService settingsService, PythonWorkerSupervisor supervisor) {
        this.settingsService = settingsService;
        this.supervisor = supervisor;
    }

    @GetMapping
    public Map<String, String> getSettings() {
        return settingsService.getAllSettings();
    }

    @PostMapping
    public Map<String, Object> updateSettings(@RequestBody Map<String, String> settings) {
        settingsService.updateSettings(settings);
        if (settings.containsKey("worker_processes") && !Boolean.getBoolean("afm.noWorkers")) {
            supervisor.resize(settingsService.getIntSetting("worker_processes", 3));
        }
        return Map.of("message", "Đã lưu cài đặt", "settings", settingsService.getAllSettings());
    }
}
