package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.prompt.ProjectAssetRepository;
import com.aifilm.automation.prompt.PromptKind;
import com.aifilm.automation.prompt.PromptReferenceRepository;
import com.aifilm.automation.prompt.PromptRepository;
import com.aifilm.automation.prompt.PromptRevisionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobQueryServiceTest {

    @Test
    void list_jobWhosePromptWasDeleted_isSkipped() {
        JobRepository jobs = mock(JobRepository.class);
        JobEntity orphan = new JobEntity(1L, 42L, PromptKind.IMAGE);
        orphan.setId(1L);
        when(jobs.findByProjectId(1L)).thenReturn(List.of(orphan));
        JobQueryService service = new JobQueryService(jobs, mock(PromptRepository.class), mock(PromptRevisionRepository.class),
                mock(PromptReferenceRepository.class), mock(AccountRepository.class), mock(JobEventRepository.class),
                mock(ProjectAssetRepository.class), mock(com.aifilm.automation.project.ProjectRepository.class));

        assertTrue(service.list(1L, null, false).isEmpty());
    }
}
