package com.aifilm.automation.project;

import com.aifilm.automation.job.JobEntity;
import com.aifilm.automation.job.JobRepository;
import com.aifilm.automation.job.JobStatus;
import com.aifilm.automation.job.JobTransitions;
import com.aifilm.automation.prompt.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Nạp lại prompt đã có: khi nào đưa job về hàng đợi, và kho ảnh được dọn ra sao. */
class OmniImportServiceReimportTest {

    private PromptRepository prompts;
    private PromptRevisionRepository revisions;
    private PromptReferenceRepository references;
    private ProjectAssetRepository assets;
    private JobRepository jobs;
    private JobTransitions transitions;
    private OmniImportService service;
    private PromptEntity prompt;
    private JobEntity job;

    @BeforeEach
    void setUp() {
        ProjectRepository projects = mock(ProjectRepository.class);
        prompts = mock(PromptRepository.class);
        revisions = mock(PromptRevisionRepository.class);
        references = mock(PromptReferenceRepository.class);
        assets = mock(ProjectAssetRepository.class);
        jobs = mock(JobRepository.class);
        transitions = mock(JobTransitions.class);
        service = new OmniImportService(projects, prompts, revisions, references, assets, jobs, transitions,
                TestFiles.directTx(), mock(AssetService.class));
        ProjectEntity p = new ProjectEntity();
        p.setId(1L);
        p.setCode("phim");
        when(projects.findById(1L)).thenReturn(Optional.of(p));
        when(revisions.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void existing(PromptKind kind, String extId, String importedText, String... refs) {
        prompt = new PromptEntity();
        prompt.setId(10L);
        prompt.setKind(kind);
        prompt.setExternalId(extId);
        prompt.setSeqNo(1);
        when(prompts.findByProjectId(1L)).thenReturn(List.of(prompt));
        when(prompts.findByProjectIdAndKindAndExternalId(1L, kind, extId)).thenReturn(Optional.of(prompt));
        var rev = new PromptRevisionEntity(10L, 1, importedText, PromptRevisionEntity.SOURCE_IMPORT, "x");
        when(revisions.findFirstByPromptIdAndSourceOrderByRevisionNoDesc(10L, PromptRevisionEntity.SOURCE_IMPORT)).thenReturn(Optional.of(rev));
        java.util.ArrayList<PromptReferenceEntity> list = new java.util.ArrayList<>();
        for (int i = 0; i < refs.length; i++) {
            list.add(new PromptReferenceEntity(10L, i, refs[i], "IMPORT"));
        }
        when(references.findByVideoPromptIdOrderByOrdinalAsc(10L)).thenReturn(list);
        job = new JobEntity(1L, 10L, kind);
        job.setStatus(JobStatus.SUCCESS);
        job.setFinalPath("D:/anh/" + extId + ".png");
        job.setErrorCode("E1");
        job.setErrorMessage("lỗi cũ");
        when(jobs.findByPromptId(10L)).thenReturn(Optional.of(job));
    }

    private OmniImportService.ImportResult reimport(PromptKind kind, String id, String text, String... refs) {
        return service.importLines(1L, kind, "f.xlsx", List.of(new OmniImportService.Line(1, id, text, List.of(refs), null)));
    }

    @Test
    void imagePromptChanged_requeues_clearsResult_andRemovesApprovedAsset() {
        existing(PromptKind.IMAGE, "CHAR-A-1", "cũ");
        var approved = new ProjectAssetEntity(1L, "CHAR-A-1", "D:/anh/CHAR-A-1.png", ProjectAssetEntity.SOURCE_APPROVED);
        when(assets.findByProjectIdAndExternalId(1L, "CHAR-A-1")).thenReturn(Optional.of(approved));

        var r = reimport(PromptKind.IMAGE, "CHAR-A-1", "mới");

        assertEquals(1, r.requeued());
        assertNull(job.getFinalPath());
        assertNull(job.getErrorCode());
        assertNull(job.getErrorMessage());
        verify(assets).delete(approved);
        verify(transitions).move(eq(job), eq(JobStatus.PENDING), isNull(), eq("import lại: prompt đổi ở nguồn"));
    }

    @Test
    void cancelledBecauseUserProvidedImage_isNotRequeued_whenPromptChanges() {
        existing(PromptKind.IMAGE, "CHAR-A-1", "cũ");
        job.setStatus(JobStatus.CANCELLED);
        var user = new ProjectAssetEntity(1L, "CHAR-A-1", "D:/anh/CHAR-A-1.png", ProjectAssetEntity.SOURCE_USER);
        when(assets.findByProjectIdAndExternalId(1L, "CHAR-A-1")).thenReturn(Optional.of(user));

        var r = reimport(PromptKind.IMAGE, "CHAR-A-1", "mới");

        assertEquals(1, r.updated());   // prompt mới vẫn được ghi nhận
        assertEquals(0, r.requeued());
        verify(assets, never()).delete(any());
        verify(transitions, never()).move(any(), any(), any(), anyString());
    }

    @Test
    void onlyReferenceIdsChanged_countsAsChanged_andRequeuesSuccessfulVideo() {
        existing(PromptKind.VIDEO, "001", "giống nhau", "CHAR-A-1");

        var r = reimport(PromptKind.VIDEO, "1", "giống nhau", "CHAR-A-1", "CHAR-B-1");

        assertEquals(1, r.updated());
        assertEquals(1, r.requeued());
        verify(revisions, never()).save(any());   // chữ không đổi: không thêm bản prompt mới
        verify(transitions).move(eq(job), eq(JobStatus.PENDING), isNull(), eq("import lại: ảnh reference đổi ở nguồn"));
    }

    @Test
    void samePromptAndSameRefs_isUnchanged() {
        existing(PromptKind.VIDEO, "001", "giống nhau", "CHAR-A-1");

        var r = reimport(PromptKind.VIDEO, "1", "giống nhau", "CHAR-A-1");

        assertEquals(1, r.unchanged());
        assertEquals(0, r.requeued());
        verify(transitions, never()).move(any(), any(), any(), anyString());
    }
}
