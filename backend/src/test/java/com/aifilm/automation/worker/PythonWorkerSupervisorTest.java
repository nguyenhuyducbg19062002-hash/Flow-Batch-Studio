package com.aifilm.automation.worker;

import com.aifilm.automation.job.JobResultService;
import com.aifilm.automation.service.AppSettingsService;
import com.aifilm.automation.system.DesktopWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonWorkerSupervisorTest {

    private JobResultService results;
    private AppSettingsService settings;
    private final Deque<Process> spawned = new ArrayDeque<>();
    private int spawnCalls;
    private PythonWorkerSupervisor sup;

    @BeforeEach
    void setUp() {
        results = mock(JobResultService.class);
        settings = mock(AppSettingsService.class);
        when(settings.getIntSetting(anyString(), anyInt())).thenReturn(1);
        sup = new PythonWorkerSupervisor(mock(WorkerToken.class), settings, mock(DesktopWindow.class), results) {
            @Override
            Process spawn(String id) {
                spawnCalls++;
                return spawned.poll();
            }
        };
    }

    private static Process process(boolean alive) throws InterruptedException {
        Process p = mock(Process.class);
        when(p.isAlive()).thenReturn(alive);
        when(p.descendants()).thenReturn(Stream.empty());
        when(p.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        return p;
    }

    @Test
    void spawnFailed_watchdogRetriesWithBackoff() throws Exception {
        sup.start();                       // spawn lỗi → null
        assertEquals(0, sup.aliveCount());
        sup.watchdog();                    // còn trong thời gian chờ
        assertEquals(1, spawnCalls);
        spawned.add(process(true));
        sup.watchdog();                    // tới lượt thử lại
        assertEquals(2, spawnCalls);
        assertEquals(1, sup.aliveCount());
    }

    @Test
    void deadWorker_jobsReleasedImmediatelyAndRespawned() throws Exception {
        spawned.add(process(false));
        sup.start();
        spawned.add(process(true));
        sup.watchdog();
        verify(results).releaseWorkerJobs("py-0");
        assertEquals(1, sup.aliveCount());
    }

    @Test
    void resizeShrink_killsAndReleasesJobs() throws Exception {
        when(settings.getIntSetting(anyString(), anyInt())).thenReturn(2);
        Process p0 = process(true);
        Process p1 = process(true);
        spawned.add(p0);
        spawned.add(p1);
        sup.start();
        sup.resize(1);
        verify(p1).destroy();
        verify(results).releaseWorkerJobs("py-1");
        verify(results, never()).releaseWorkerJobs("py-0");
    }

    @Test
    void kill_forcesWhenProcessDoesNotExit() throws Exception {
        Process p = process(true);
        when(p.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
        spawned.add(p);
        sup.start();
        sup.stop();
        verify(p).destroyForcibly();
    }
}
