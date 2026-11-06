package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountEntity;
import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.account.AccountStatus;
import com.aifilm.automation.job.WorkerReports.FailureReport;
import com.aifilm.automation.job.WorkerReports.GeneratedReport;
import com.aifilm.automation.project.FlowLinkRepository;
import com.aifilm.automation.project.ProjectRepository;
import com.aifilm.automation.prompt.PromptKind;
import com.aifilm.automation.service.AppSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobResultServiceTest {

    private JobRepository jobs;
    private AccountRepository accounts;
    private ProjectRepository projects;
    private JobResultService service;

    @BeforeEach
    void setUp() {
        jobs = mock(JobRepository.class);
        accounts = mock(AccountRepository.class);
        projects = mock(ProjectRepository.class);
        when(projects.findById(anyLong())).thenReturn(Optional.empty());
        service = new JobResultService(jobs, accounts, projects, new JobTransitions(mock(JobEventRepository.class)),
                mock(AppSettingsService.class), mock(FlowLinkRepository.class), mock(ReviewService.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    private JobEntity generating(long id, String worker, Long accountId) {
        JobEntity j = new JobEntity(1L, id, PromptKind.IMAGE);
        j.setId(id);
        j.setStatus(JobStatus.GENERATING);
        j.setWorkerId(worker);
        j.setAccountId(accountId);
        when(jobs.findById(id)).thenReturn(Optional.of(j));
        return j;
    }

    private AccountEntity leased(long id, long jobId) {
        AccountEntity a = new AccountEntity();
        a.setId(id);
        a.lease(jobId, LocalDateTime.now());
        when(accounts.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void reaper_oneBadJobDoesNotBlockOthers() {
        JobEntity bad = generating(1L, "py-0", 10L);
        JobEntity good = generating(2L, "py-1", 11L);
        when(jobs.findById(1L)).thenThrow(new IllegalStateException("hỏng"));
        leased(11L, 2L);
        when(jobs.findByStatusAndHeartbeatAtBefore(any(), any())).thenReturn(List.of(bad, good));

        service.reapStaleJobs();

        assertEquals(JobStatus.PENDING, good.getStatus());
        assertEquals(1, good.getAttempt());
    }

    @Test
    void failed_accountDeleted_stillRequeued() {
        JobEntity j = generating(1L, "py-0", 99L);
        when(accounts.findById(99L)).thenReturn(Optional.empty());

        service.onFailed(1L, new FailureReport("py-0", "TRANSIENT", "x"));

        assertEquals(JobStatus.PENDING, j.getStatus());
        assertNull(j.getAccountId());
    }

    @Test
    void failed_transientWithCancelRequested_becomesCancelled() {
        JobEntity j = generating(1L, "py-0", 10L);
        j.setCancelRequested(true);
        AccountEntity a = leased(10L, 1L);

        service.onFailed(1L, new FailureReport("py-0", "TRANSIENT", "x"));

        assertEquals(JobStatus.CANCELLED, j.getStatus());
        assertFalse(j.isCancelRequested());
        assertEquals(AccountStatus.READY, a.getStatus());
    }

    @Test
    void generated_withCancelRequested_becomesCancelled() {
        JobEntity j = generating(1L, "py-0", 10L);
        j.setCancelRequested(true);
        AccountEntity a = leased(10L, 1L);

        service.onGenerated(1L, new GeneratedReport("py-0", "C:/nowhere/x.png", null, null));

        assertEquals(JobStatus.CANCELLED, j.getStatus());
        assertNull(j.getPreviewPath());
        assertEquals(AccountStatus.READY, a.getStatus());
    }

    @Test
    void recoverAfterRestart_cancelRequested_becomesCancelled() {
        JobEntity cancelled = generating(1L, "py-0", 10L);
        cancelled.setCancelRequested(true);
        JobEntity normal = generating(2L, "py-1", 11L);
        when(jobs.findByStatus(JobStatus.GENERATING)).thenReturn(List.of(cancelled, normal));
        when(jobs.findByStatus(JobStatus.DOWNLOADING)).thenReturn(List.of());
        when(projects.findByStatusOrderByIdAsc(any())).thenReturn(List.of());

        service.recoverAfterRestart();

        assertEquals(JobStatus.CANCELLED, cancelled.getStatus());
        assertEquals(JobStatus.PENDING, normal.getStatus());
    }

    @Test
    void releaseWorkerJobs_requeuesWithoutAttemptAndReleasesAccount() {
        JobEntity j = generating(1L, "py-0", 10L);
        AccountEntity a = leased(10L, 1L);
        when(jobs.findByStatusAndWorkerId(JobStatus.GENERATING, "py-0")).thenReturn(List.of(j));

        service.releaseWorkerJobs("py-0");

        assertEquals(JobStatus.PENDING, j.getStatus());
        assertEquals(0, j.getAttempt());
        assertNull(j.getWorkerId());
        assertEquals(AccountStatus.READY, a.getStatus());
    }
}
