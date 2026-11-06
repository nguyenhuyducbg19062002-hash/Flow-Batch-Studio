package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountEntity;
import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.account.AccountStatus;
import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.project.ProjectRepository;
import com.aifilm.automation.project.ProjectStatus;
import com.aifilm.automation.prompt.PromptKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Job "Sửa trên Flow" có tài khoản đích không dùng được không được chặn các job khác của phim. */
class JobClaimServiceFlowEditTest {

    private JobRepository jobs;
    private AccountRepository accounts;
    private JobClaimService service;
    private final List<JobEntity> pending = new ArrayList<>();
    private AccountEntity other;

    @BeforeEach
    void setUp() {
        jobs = mock(JobRepository.class);
        accounts = mock(AccountRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        JobPayloadFactory payloads = mock(JobPayloadFactory.class);
        ProjectEntity p = new ProjectEntity();
        p.setId(1L);
        p.setStatus(ProjectStatus.RUNNING);
        p.setRunImages(true);
        p.setRunVideos(true);
        when(projects.findByStatusOrderByIdAsc(ProjectStatus.RUNNING)).thenReturn(List.of(p));
        when(payloads.forGenerate(any(), any(), any())).thenReturn(mock(JobPayload.class));
        when(jobs.findNextBatch(anyLong(), anyInt(), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            Collection<?> skip = inv.getArgument(4);
            return pending.stream().filter(j -> j.getStatus() == JobStatus.PENDING && !skip.contains(j.getId())).findFirst();
        });
        other = account(20L, AccountStatus.READY);
        when(accounts.findNextAvailable(anyString(), any())).thenReturn(Optional.of(other));
        service = new JobClaimService(jobs, accounts, projects, new JobTransitions(mock(JobEventRepository.class)), payloads,
                null, null, new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    private JobEntity job(long id) {
        JobEntity j = new JobEntity(1L, id, PromptKind.VIDEO);
        j.setId(id);
        pending.add(j);
        return j;
    }

    private JobEntity flowEdit(long id, long accountId) {
        JobEntity j = job(id);
        j.setFlowMediaUrl("https://flow.google.com/project/x/edit/y");
        j.setFlowAccountId(accountId);
        j.setEditInstruction("cho Caleb cười");
        j.setRunPriority(5);
        return j;
    }

    private AccountEntity account(long id, AccountStatus status) {
        AccountEntity a = new AccountEntity();
        a.setId(id);
        a.setEmail("a" + id + "@x");
        a.setStatus(status);
        when(accounts.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void flowEditTargetBusy_skippedAndNextJobClaimed() {
        JobEntity edit = flowEdit(1L, 10L);
        account(10L, AccountStatus.LEASED);
        JobEntity normal = job(2L);

        assertTrue(service.claim("py-0").isPresent());

        assertEquals(JobStatus.PENDING, edit.getStatus());
        assertEquals("cho Caleb cười", edit.getEditInstruction());
        assertEquals(JobStatus.GENERATING, normal.getStatus());
        assertEquals(20L, normal.getAccountId());
    }

    @Test
    void flowEditTargetDeleted_editDroppedWithMessage() {
        JobEntity edit = flowEdit(1L, 10L);
        when(accounts.findById(10L)).thenReturn(Optional.empty());
        JobEntity normal = job(2L);

        assertTrue(service.claim("py-0").isPresent());

        assertEquals(JobStatus.CANCELLED, edit.getStatus());
        assertNull(edit.getEditInstruction());
        assertNull(edit.getFlowAccountId());
        assertTrue(edit.getErrorMessage().contains("đã bị xóa"));
        assertEquals(JobStatus.GENERATING, normal.getStatus());
    }

    @Test
    void flowEditTargetDisabled_editDropped() {
        JobEntity edit = flowEdit(1L, 10L);
        account(10L, AccountStatus.DISABLED);
        job(2L);

        service.claim("py-0");

        assertEquals(JobStatus.CANCELLED, edit.getStatus());
        assertEquals(10L, edit.getFlowAccountId());
        assertTrue(edit.getErrorMessage().contains("đang tắt"));
    }

    @Test
    void pendingJobWithCancelRequested_becomesCancelledNotClaimed() {
        JobEntity cancelled = job(1L);
        cancelled.setCancelRequested(true);
        JobEntity normal = job(2L);

        service.claim("py-0");

        assertEquals(JobStatus.CANCELLED, cancelled.getStatus());
        assertFalse(cancelled.isCancelRequested());
        assertEquals(JobStatus.GENERATING, normal.getStatus());
    }
}
