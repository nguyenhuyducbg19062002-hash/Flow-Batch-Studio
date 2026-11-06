package com.aifilm.automation.project;

import com.aifilm.automation.job.JobEntity;
import com.aifilm.automation.job.JobRepository;
import com.aifilm.automation.job.JobStatus;
import com.aifilm.automation.job.JobTransitions;
import com.aifilm.automation.prompt.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AssetServiceImportFolderTest {

    @TempDir
    Path tmp;

    private ProjectAssetRepository assets;
    private JobRepository jobs;
    private JobTransitions transitions;
    private AssetService service;
    private Path imageDir;
    private PromptEntity charA;
    private JobEntity jobA;

    @BeforeEach
    void setUp() {
        ProjectRepository projects = mock(ProjectRepository.class);
        assets = mock(ProjectAssetRepository.class);
        PromptRepository prompts = mock(PromptRepository.class);
        PromptReferenceRepository references = mock(PromptReferenceRepository.class);
        jobs = mock(JobRepository.class);
        transitions = mock(JobTransitions.class);
        service = new AssetService(projects, assets, prompts, references, jobs, transitions, TestFiles.directTx());

        imageDir = tmp.resolve("Omni/Ảnh tham chiếu");
        ProjectEntity p = new ProjectEntity();
        p.setId(1L);
        p.setImageDir(imageDir.toString());
        when(projects.findById(1L)).thenReturn(Optional.of(p));

        charA = new PromptEntity();
        charA.setId(10L);
        charA.setKind(PromptKind.IMAGE);
        charA.setExternalId("CHAR-A-1");
        when(prompts.findByProjectId(1L)).thenReturn(List.of(charA));
        when(references.findDistinctRefIds(1L)).thenReturn(List.of("CHAR-A-1", "LOC-1"));
        jobA = new JobEntity(1L, 10L, PromptKind.IMAGE);
        when(jobs.findByPromptId(10L)).thenReturn(Optional.of(jobA));
        when(assets.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void approvedResultInImageDir_isNotTreatedAsUserImage_jobNotCancelled() throws IOException {
        Path approved = TestFiles.png(imageDir.resolve("CHAR-A-1.png"));
        jobA.setStatus(JobStatus.SUCCESS);
        jobA.setFinalPath(approved.toString());
        ProjectAssetEntity a = new ProjectAssetEntity(1L, "CHAR-A-1", approved.toString(), ProjectAssetEntity.SOURCE_APPROVED);
        when(assets.findByProjectId(1L)).thenReturn(List.of(a));

        var r = service.importFolder(1L, tmp.resolve("Omni").toString());

        assertEquals(0, r.added() + r.replaced());
        verify(assets, never()).save(any());
        verify(transitions, never()).move(any(), any(), any(), anyString());
    }

    @Test
    void requeuedJob_oldResultSnapshottedBeforeReimport_isSkipped() throws IOException {
        // prompt đổi → job về PENDING, asset APPROVED đã xóa; file cũ vẫn nằm trong Folder Ảnh
        Path old = TestFiles.png(imageDir.resolve("CHAR-A-1.png"));
        jobA.setStatus(JobStatus.PENDING);

        var r = service.importFolder(1L, tmp.resolve("Omni").toString(), Set.of(old.toAbsolutePath().normalize()));

        assertEquals(0, r.added());
        assertEquals(JobStatus.PENDING, jobA.getStatus());
        verify(transitions, never()).move(any(), any(), any(), anyString());
    }

    @Test
    void userImageElsewhere_cancelsPendingJob_andIsCopiedIntoImageDir() throws IOException {
        TestFiles.png(tmp.resolve("Omni/goc/char-a-1.JPG"));   // tên không phân biệt hoa thường
        jobA.setStatus(JobStatus.PENDING);

        var r = service.importFolder(1L, tmp.resolve("Omni").toString());

        assertEquals(1, r.added());
        assertEquals(1, r.skippedJobs());
        assertTrue(Files.isRegularFile(imageDir.resolve("CHAR-A-1.jpg")));
        verify(assets).save(argThat(a -> a.getExternalId().equals("CHAR-A-1") && a.getSource().equals("USER")));
        verify(transitions).move(eq(jobA), eq(JobStatus.CANCELLED), isNull(), anyString());
    }

    @Test
    void sameIdInSeveralFolders_fileAlreadyInImageDirWins_othersWarned() throws IOException {
        Path keep = TestFiles.png(imageDir.resolve("LOC-1.png"), (byte) 1);
        TestFiles.png(tmp.resolve("Omni/goc/LOC-1.png"), (byte) 2);
        TestFiles.png(tmp.resolve("Omni/khac/LOC-1.png"), (byte) 3);

        var r = service.importFolder(1L, tmp.resolve("Omni").toString());

        assertEquals(1, r.added());
        assertEquals(2, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("trùng ID LOC-1"));
        assertEquals((byte) 1, Files.readAllBytes(keep)[10]);   // không bị chép đè
    }

    @Test
    void replacedCountedPerDistinctId() throws IOException {
        TestFiles.png(tmp.resolve("Omni/a/LOC-1.png"));
        TestFiles.png(tmp.resolve("Omni/b/LOC-1.png"));
        when(assets.findByProjectIdAndExternalId(1L, "LOC-1"))
                .thenReturn(Optional.of(new ProjectAssetEntity(1L, "LOC-1", "x.png", ProjectAssetEntity.SOURCE_USER)));

        var r = service.importFolder(1L, tmp.resolve("Omni").toString());

        assertEquals(1, r.replaced());
        assertEquals(0, r.added());
    }

    @Test
    void brokenOrEmptyImage_isSkippedWithWarning() throws IOException {
        Path broken = tmp.resolve("Omni/LOC-1.png");
        Files.createDirectories(broken.getParent());
        Files.write(broken, new byte[]{1, 2, 3});
        TestFiles.png(tmp.resolve("Omni/khong-thuoc-phim.png"));

        var r = service.importFolder(1L, tmp.resolve("Omni").toString());

        assertEquals(0, r.added());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("LOC-1.png"));
        assertEquals(List.of("khong-thuoc-phim.png"), r.notReferencedNames());
    }
}
