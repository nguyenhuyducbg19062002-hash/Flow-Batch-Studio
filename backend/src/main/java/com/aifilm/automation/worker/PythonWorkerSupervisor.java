package com.aifilm.automation.worker;

import com.aifilm.automation.AiFlowAutomationApplication;
import com.aifilm.automation.job.JobResultService;
import com.aifilm.automation.service.AppSettingsService;
import com.aifilm.automation.system.AppPaths;
import com.aifilm.automation.system.DesktopWindow;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bật N tiến trình Python worker khi app khởi động, bật lại khi một tiến trình chết, tắt hết (kể cả Edge con) khi app tắt.
 * Python: ưu tiên bản embeddable đóng gói kèm (<home>/python/python.exe); khi chạy dev thì dùng "python" trên PATH.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PythonWorkerSupervisor {

    private final WorkerToken token;
    private final AppSettingsService settings;
    private final DesktopWindow desktopWindow;
    private final JobResultService results;
    private final List<Process> workers = new ArrayList<>();
    /** Bật worker lỗi: lần thử kế tiếp ở lượt watchdog thứ mấy, và số lần lỗi liên tiếp (giãn dần tới ~10 phút). */
    private final Map<Integer, Long> retryTick = new HashMap<>();
    private final Map<Integer, Integer> spawnFailures = new HashMap<>();
    private long tick;
    private volatile boolean stopping;

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)   // sau JobResultService.recoverAfterRestart
    public synchronized void start() {
        if (Boolean.getBoolean("afm.noWorkers")) {
            log.info("afm.noWorkers=true: không bật worker Python (chạy worker tay để debug)");
        } else {
            int n = Math.max(1, settings.getIntSetting("worker_processes", 3));
            for (int i = 0; i < n; i++) {
                workers.add(spawnSlot(i));
            }
            log.info("Đã bật {} worker Python", n);
        }
        desktopWindow.openOnStartup("http://127.0.0.1:" + AiFlowAutomationApplication.PORT);
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public synchronized void watchdog() {
        if (stopping) {
            return;
        }
        tick++;
        for (int i = 0; i < workers.size(); i++) {
            Process p = workers.get(i);
            if (p != null && !p.isAlive()) {
                log.warn("Worker py-{} đã dừng (exit {}), bật lại", i, p.exitValue());
                releaseJobs(i);
                workers.set(i, spawnSlot(i));
            } else if (p == null && tick >= retryTick.getOrDefault(i, 0L)) {
                workers.set(i, spawnSlot(i));
            }
        }
    }

    public synchronized int aliveCount() {
        return (int) workers.stream().filter(p -> p != null && p.isAlive()).count();
    }

    /** Đổi số worker lúc đang chạy (khi người dùng lưu cài đặt). */
    public synchronized void resize(int n) {
        n = Math.max(1, n);
        while (workers.size() < n) {
            workers.add(spawnSlot(workers.size()));
        }
        while (workers.size() > n) {
            int i = workers.size() - 1;
            kill(workers.remove(i));
            retryTick.remove(i);
            spawnFailures.remove(i);
            releaseJobs(i);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        stopping = true;
        workers.forEach(this::kill);
        workers.clear();
    }

    private void kill(Process p) {
        if (p == null) {
            return;
        }
        List<ProcessHandle> children = p.descendants().toList();   // Edge do Playwright mở
        children.forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    /** Worker vừa chết / bị tắt: job nó đang làm về hàng đợi ngay, không chờ reaper 5 phút. */
    private void releaseJobs(int slot) {
        try {
            results.releaseWorkerJobs("py-" + slot);
        } catch (RuntimeException e) {
            log.error("Không trả được job của worker py-{} về hàng đợi: {}", slot, e.toString());
        }
    }

    private Process spawnSlot(int i) {
        Process p = spawn("py-" + i);
        if (p != null) {
            retryTick.remove(i);
            spawnFailures.remove(i);
        } else {
            int fails = spawnFailures.merge(i, 1, Integer::sum);
            retryTick.put(i, tick + Math.min(1L << Math.min(fails, 6), 40L));
        }
        return p;
    }

    Process spawn(String id) {
        Path home = AppPaths.home();
        Path bundled = home.resolve("python").resolve("python.exe");
        Path script = home.resolve("engine").resolve("worker_main.py");
        String python = Files.exists(bundled) ? bundled.toString() : System.getProperty("afm.python", "python");
        List<String> cmd = List.of(python, "-u", script.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(home.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(AppPaths.logs().resolve("worker-" + id + ".log").toFile()));
        Map<String, String> env = pb.environment();
        env.put("AFM_API", "http://127.0.0.1:" + AiFlowAutomationApplication.PORT + "/api/worker");
        env.put("AFM_WORKER_TOKEN", token.value());
        env.put("AFM_WORKER_ID", id);
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONIOENCODING", "utf-8");
        try {
            return pb.start();
        } catch (IOException e) {
            log.error("Không chạy được worker Python {}: {}", cmd, e.getMessage());
            return null;   // watchdog thử lại sau (giãn dần); trang Hệ thống báo thiếu worker
        }
    }
}
