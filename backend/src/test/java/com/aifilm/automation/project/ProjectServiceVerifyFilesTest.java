package com.aifilm.automation.project;

import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.job.*;
import com.aifilm.automation.prompt.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Dời thư mục phim: file đã duyệt vẫn còn (ở Folder Ảnh / Video mới) thì chỉ sửa đường dẫn, không tạo lại. */
class ProjectServiceVerifyFilesTest {

    @TempDir
    Path tmp;

    private ProjectRepository projects;
    private JobRepository jobs;
    private PromptRepository prompts;
    private ProjectAssetRepository assets;
    private JobTransitions transitions;
    private ProjectService service;
    private ProjectEntity project;
    private JobEntity imageJob;
    private ProjectAssetEntity approved;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        jobs = mock(JobRepository.class);
        prompts = mock(PromptRepository.class);
        assets = mock(ProjectAssetRepository.class);
        transitions = mock(JobTransitions.class);
        service = new ProjectService(projects, jobs, mock(JobEventRepository.class), prompts, mock(PromptRevisionRepository.class),
                mock(PromptReferenceRepository.class), mock(AccountRepository.class), assets, transitions,
                mock(OmniImportService.class), mock(AssetService.class), mock(FlowLinkRepository.class));
        project = new ProjectEntity();
        project.setId(1L);
        project.setImageDir(tmp.resolve("moi/anh").toString());
        project.setVideoDir(tmp.resolve("moi/phim").toString());
        when(projects.findById(1L)).thenReturn(Optional.of(project));

        PromptEntity prompt = new PromptEntity();
        prompt.setId(10L);
        prompt.setExternalId("CHAR-A-1");
        when(prompts.findById(10L)).thenReturn(Optional.of(prompt));
        imageJob = new JobEntity(1L, 10L, PromptKind.IMAGE);
        imageJob.setId(100L);
        imageJob.setStatus(JobStatus.SUCCESS);
        imageJob.setFinalPath(tmp.resolve("cu/anh/CHAR-A-1.png").toString());
        approved = new ProjectAssetEntity(1L, "CHAR-A-1", imageJob.getFinalPath(), ProjectAssetEntity.SOURCE_APPROVED);
        when(jobs.findByProjectIdAndStatus(1L, JobStatus.SUCCESS)).thenReturn(List.of(imageJob));
        when(assets.findByProjectIdAndExternalId(1L, "CHAR-A-1")).thenReturn(Optional.of(approved));
        when(assets.findByProjectId(1L)).thenReturn(List.of(approved));
    }

    @Test
    void movedFolder_fileFoundInNewImageDir_pathUpdated_nothingRequeued() throws IOException {
        Path moved = TestFiles.png(tmp.resolve("moi/anh/CHAR-A-1.png"));

        var r = service.verifyFiles(1L);

        assertEquals(0, r.requeued());
        assertEquals(moved.toString(), imageJob.getFinalPath());
        assertEquals(moved.toString(), approved.getFilePath());
        verify(transitions, never()).move(any(), any(), any(), anyString());
        verify(assets, never()).delete(any());
    }

    @Test
    void fileReallyGone_isRequeued_andApprovedAssetRemoved() {
        var r = service.verifyFiles(1L);

        assertEquals(1, r.requeued());
        assertEquals(1, r.missing());
        assertNull(imageJob.getFinalPath());
        verify(transitions).move(eq(imageJob), eq(JobStatus.PENDING), isNull(), anyString());
        verify(assets).delete(approved);
    }

    @Test
    void videoFoundInSubfolderOfNewVideoDir_caseInsensitive() throws IOException {
        JobEntity video = new JobEntity(1L, 11L, PromptKind.VIDEO);
        video.setStatus(JobStatus.SUCCESS);
        video.setFinalPath(tmp.resolve("cu/phim/tap-5_001.mp4").toString());
        when(jobs.findByProjectIdAndStatus(1L, JobStatus.SUCCESS)).thenReturn(List.of(video));
        when(assets.findByProjectId(1L)).thenReturn(List.of());
        Path moved = tmp.resolve("moi/phim/xong/TAP-5_001.MP4");
        Files.createDirectories(moved.getParent());
        byte[] b = new byte[200];
        b[4] = 'f'; b[5] = 't'; b[6] = 'y'; b[7] = 'p';
        Files.write(moved, b);

        var r = service.verifyFiles(1L);

        assertEquals(0, r.requeued());
        assertEquals(moved.toString(), video.getFinalPath());
    }

    @Test
    void userAssetMoved_pathUpdated_notDeleted() throws IOException {
        ProjectAssetEntity user = new ProjectAssetEntity(1L, "LOC-1", tmp.resolve("cu/anh/LOC-1.jpg").toString(), ProjectAssetEntity.SOURCE_USER);
        when(jobs.findByProjectIdAndStatus(1L, JobStatus.SUCCESS)).thenReturn(List.of());
        when(assets.findByProjectId(1L)).thenReturn(List.of(user));
        Path moved = TestFiles.png(tmp.resolve("moi/anh/LOC-1.jpg"));

        var r = service.verifyFiles(1L);

        assertEquals(0, r.missing() + r.broken());
        assertEquals(moved.toString(), user.getFilePath());
        verify(assets, never()).delete(any());
    }
}
