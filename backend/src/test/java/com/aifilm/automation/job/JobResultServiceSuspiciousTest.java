package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountEntity;
import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.account.AccountStatus;
import com.aifilm.automation.job.WorkerReports.FailureReport;
import com.aifilm.automation.project.FlowLinkRepository;
import com.aifilm.automation.project.ProjectRepository;
import com.aifilm.automation.prompt.PromptKind;
import com.aifilm.automation.service.AppSettingsService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Flow báo "unusual activity": tài khoản nghỉ, lệnh đó quay về hàng đợi (không tính lượt thử) để tài khoản khác làm tiếp. */
class JobResultServiceSuspiciousTest {

    @Test
    void suspiciousReport_coolsTheAccountAndRequeuesTheJobForAnotherAccount() {
        JobRepository jobs = mock(JobRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getIntSetting(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        JobResultService service = new JobResultService(jobs, accounts, mock(ProjectRepository.class),
                new JobTransitions(mock(JobEventRepository.class)), settings, mock(FlowLinkRepository.class), mock(ReviewService.class),
                new org.springframework.transaction.support.TransactionTemplate(mock(org.springframework.transaction.PlatformTransactionManager.class)));

        AccountEntity acc = new AccountEntity();
        acc.setId(1L);
        acc.setEmail("a@x.com");
        acc.setStatus(AccountStatus.LEASED);
        JobEntity job = new JobEntity(1L, 10L, PromptKind.IMAGE);
        job.setId(5L);
        job.setStatus(JobStatus.GENERATING);
        job.setWorkerId("py-0");
        job.setAccountId(1L);
        when(jobs.findById(5L)).thenReturn(Optional.of(job));
        when(accounts.findById(1L)).thenReturn(Optional.of(acc));

        service.onFailed(5L, new FailureReport("py-0", "SUSPICIOUS", "Flow nghi ngờ hoạt động tự động"));

        assertEquals(AccountStatus.COOLDOWN, acc.getStatus());
        assertTrue(acc.getCooldownUntil().isAfter(LocalDateTime.now().plusMinutes(25)), "nghỉ ~30 phút");
        assertEquals(JobStatus.PENDING, job.getStatus());
        assertEquals(0, job.getAttempt(), "không tính lượt thử");
        assertNull(job.getAccountId(), "bỏ gắn tài khoản cũ để tài khoản khác nhận");
    }
}
