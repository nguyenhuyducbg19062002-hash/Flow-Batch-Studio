package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountEntity;
import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.account.AccountStatus;
import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.project.ProjectRepository;
import com.aifilm.automation.project.ProjectStatus;
import com.aifilm.automation.project.Provider;
import com.aifilm.automation.project.RunMode;
import com.aifilm.automation.prompt.PromptKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Chạy song song: cấp thêm job cho tài khoản đang giữ phiên; và nhả tài khoản khi còn job khác đang chạy. */
class JobClaimServiceMoreTest {

    private JobRepository jobs;
    private AccountRepository accounts;
    private ProjectRepository projects;
    private JobPayloadFactory payloads;
    private JobClaimService service;

    private AccountEntity account;
    private ProjectEntity project;
    private JobEntity running;
    private JobEntity candidate;

    @BeforeEach
    void setUp() {
        jobs = mock(JobRepository.class);
        accounts = mock(AccountRepository.class);
        projects = mock(ProjectRepository.class);
        payloads = mock(JobPayloadFactory.class);
        service = new JobClaimService(jobs, accounts, projects, mock(JobTransitions.class), payloads, mock(com.aifilm.automation.prompt.ProjectAssetRepository.class), mock(com.aifilm.automation.prompt.PromptReferenceRepository.class), null);

        account = new AccountEntity();
        account.setId(7L);
        account.setEmail("a@x.com");
        account.setStatus(AccountStatus.LEASED);
        account.setMaxConcurrent(3);

        project = new ProjectEntity();
        project.setId(1L);
        project.setStatus(ProjectStatus.RUNNING);
        project.setRunImages(true);
        project.setRunVideos(true);
        project.setRunMode(RunMode.BACKGROUND);
        project.setProvider(Provider.FLOW_BROWSER);
        project.setMaxParallel(6);

        running = new JobEntity(1L, 10L, PromptKind.VIDEO);
        running.setId(100L);
        running.setStatus(JobStatus.GENERATING);
        running.setWorkerId("py-0");
        running.setAccountId(7L);

        candidate = new JobEntity(1L, 11L, PromptKind.VIDEO);
        candidate.setId(101L);

        when(accounts.findById(7L)).thenReturn(Optional.of(account));
        when(jobs.findByAccountIdAndStatus(7L, JobStatus.GENERATING)).thenReturn(List.of(running));
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(jobs.countByProjectIdAndStatus(1L, JobStatus.GENERATING)).thenReturn(1L);
        when(jobs.findNextBatch(anyLong(), anyInt(), anyInt(), anyInt(), any())).thenReturn(Optional.of(candidate));
        when(payloads.forGenerate(any(), any(), any())).thenReturn(new JobPayload(101L, "VIDEO", "FLOW_BROWSER", "BACKGROUND",
                "2", "p", List.of(), Map.of(), "s", null, "16:9", 600, false, null, "720p", null, null, null, 1.0, null, 10, null));
    }

    @Test
    void grantsAnotherJob_toTheAccountTheWorkerHolds() {
        var p = service.claimMoreInTx("py-0", 7L);
        assertTrue(p.isPresent());
        assertEquals(101L, p.get().jobId());
        assertEquals(7L, candidate.getAccountId());
        assertEquals("py-0", candidate.getWorkerId());
        assertEquals(AccountStatus.LEASED, account.getStatus());
    }

    @Test
    void refuses_whenAccountIsAtItsConcurrencyLimit() {
        account.setMaxConcurrent(1);   // mặc định cũ: chạy lần lượt
        assertTrue(service.claimMoreInTx("py-0", 7L).isEmpty());
        account.setMaxConcurrent(null);
        assertTrue(service.claimMoreInTx("py-0", 7L).isEmpty());
    }

    @Test
    void refuses_whenAnotherWorkerHoldsTheAccount() {
        assertTrue(service.claimMoreInTx("py-1", 7L).isEmpty());
    }

    @Test
    void refuses_whenAccountIsNotLeasedAnymore() {
        account.setStatus(AccountStatus.COOLDOWN);
        assertTrue(service.claimMoreInTx("py-0", 7L).isEmpty());
        account.setStatus(AccountStatus.READY);
        assertTrue(service.claimMoreInTx("py-0", 7L).isEmpty());
    }

    @Test
    void refuses_whenProjectIsPausedOrAtItsOwnParallelLimit() {
        project.setStatus(ProjectStatus.PAUSED);
        assertTrue(service.claimMoreInTx("py-0", 7L).isEmpty());
        project.setStatus(ProjectStatus.RUNNING);
        project.setRunImages(true);
        project.setRunVideos(true);
        when(jobs.countByProjectIdAndStatus(1L, JobStatus.GENERATING)).thenReturn(6L);
        assertTrue(service.claimMoreInTx("py-0", 7L).isEmpty());
    }

    @Test
    void refuses_editOnFlowJobs() {
        candidate.setEditInstruction("làm sáng hơn");
        assertTrue(service.claimMoreInTx("py-0", 7L).isEmpty());
    }

    @Test
    void refuses_whenNothingIsRunnable() {
        when(jobs.findNextBatch(anyLong(), anyInt(), anyInt(), anyInt(), any())).thenReturn(Optional.empty());
        assertTrue(service.claimMoreInTx("py-0", 7L).isEmpty());
    }

    // ---- nhả tài khoản khi còn job khác đang chạy ----

    @Test
    void release_keepsLeased_untilTheLastJobFinishes() {
        account.releaseOk(true);
        assertEquals(AccountStatus.LEASED, account.getStatus());
        account.releaseOk(false);
        assertEquals(AccountStatus.READY, account.getStatus());
    }

    @Test
    void release_neverFlipsACoolingDownAccountBackToReady() {
        account.cooldown(LocalDateTime.now().plusHours(6), "hết quota");
        account.releaseOk(true);
        account.releaseOk(false);
        assertEquals(AccountStatus.COOLDOWN, account.getStatus());
        account.setStatus(AccountStatus.NEED_LOGIN);
        account.releaseOk(false);
        assertEquals(AccountStatus.NEED_LOGIN, account.getStatus());
    }

    @Test
    void releaseAfterError_followsTheSameRule_andStillCoolsDownAfterThreeInARow() {
        account.releaseAfterError("lỗi 1", true);
        assertEquals(AccountStatus.LEASED, account.getStatus());
        account.releaseAfterError("lỗi 2", false);
        assertEquals(AccountStatus.READY, account.getStatus());
        account.setStatus(AccountStatus.LEASED);
        account.releaseAfterError("lỗi 3", true);   // lần thứ 3 liên tiếp: nghỉ dù còn job khác
        assertEquals(AccountStatus.COOLDOWN, account.getStatus());
    }

    @Test
    void effectiveMaxConcurrent_isClamped() {
        account.setMaxConcurrent(null);
        assertEquals(1, account.effectiveMaxConcurrent());
        account.setMaxConcurrent(0);
        assertEquals(1, account.effectiveMaxConcurrent());
        account.setMaxConcurrent(99);
        assertEquals(5, account.effectiveMaxConcurrent());
    }
}
