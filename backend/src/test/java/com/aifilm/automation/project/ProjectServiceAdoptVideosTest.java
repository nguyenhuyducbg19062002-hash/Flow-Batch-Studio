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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Tạo lại phim / nạp lại prompt: video đã duyệt từ trước vẫn còn trong Folder Video thì cảnh đó được đánh dấu xong. */
class ProjectServiceAdoptVideosTest {

    @TempDir
    Path tmp;

    private JobRepository jobs;
    private JobTransitions transitions;
    private ProjectService service;
    private final List<PromptEntity> videos = new ArrayList<>();
    private final List<JobEntity> jobList = new ArrayList<>();
    private Path videoDir;

    @BeforeEach
    void setUp() {
        ProjectRepository projects = mock(ProjectRepository.class);
        PromptRepository prompts = mock(PromptRepository.class);
        jobs = mock(JobRepository.class);
        transitions = mock(JobTransitions.class);
        // giả lập chuyển trạng thái thật để kiểm tra trạng thái cuối
        doAnswer(i -> {
            JobEntity j = i.getArgument(0);
            j.setStatus(i.getArgument(1));
            return null;
        }).when(transitions).move(any(), any(), any(), anyString());
        service = new ProjectService(projects, jobs, mock(JobEventRepository.class), prompts, mock(PromptRevisionRepository.class),
                mock(PromptReferenceRepository.class), mock(AccountRepository.class), mock(ProjectAssetRepository.class), transitions,
                mock(OmniImportService.class), mock(AssetService.class), mock(FlowLinkRepository.class));
        videoDir = tmp.resolve("phim");
        ProjectEntity project = new ProjectEntity();
        project.setId(1L);
        project.setCode("kb-ha-tap-6");
        project.setVideoDir(videoDir.toString());
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(prompts.findByProjectId(1L)).thenReturn(videos);
        when(jobs.findByProjectId(1L)).thenReturn(jobList);
    }

    private JobEntity scene(long id, int stt, JobStatus status) {
        PromptEntity p = new PromptEntity();
        p.setId(id);
        p.setKind(PromptKind.VIDEO);
        p.setExternalId(String.valueOf(stt));
        p.setSeqNo(stt);
        p.setSummaryVi("Cảnh EP06-S1\n" + stt + ". mô tả");
        videos.add(p);
        JobEntity j = new JobEntity(1L, id, PromptKind.VIDEO);
        j.setId(100 + id);
        j.setStatus(status);
        jobList.add(j);
        return j;
    }

    private Path mp4(String name) throws IOException {
        Files.createDirectories(videoDir);
        byte[] b = new byte[200];
        b[4] = 'f'; b[5] = 't'; b[6] = 'y'; b[7] = 'p';
        return Files.write(videoDir.resolve(name), b);
    }

    @Test
    void existingVideoMarksSceneDone_othersStayPending() throws IOException {
        JobEntity s1 = scene(1, 1, JobStatus.PENDING);
        JobEntity s2 = scene(2, 2, JobStatus.PENDING);
        JobEntity s3 = scene(3, 3, JobStatus.FAILED);
        Path f1 = mp4("EP06-S1-01.mp4");
        Path f3 = mp4("EP06-S1-03.mp4");

        assertEquals(2, service.adoptExistingVideos(1L));

        assertEquals(JobStatus.SUCCESS, s1.getStatus());
        assertEquals(f1.toString(), s1.getFinalPath());
        assertEquals(JobStatus.PENDING, s2.getStatus());
        assertEquals(JobStatus.SUCCESS, s3.getStatus());
        assertEquals(f3.toString(), s3.getFinalPath());
        verify(jobs).save(s1);
    }

    @Test
    void brokenFileIsIgnored_sceneWillBeRegenerated() throws IOException {
        JobEntity s1 = scene(1, 1, JobStatus.PENDING);
        Files.createDirectories(videoDir);
        Files.writeString(videoDir.resolve("EP06-S1-01.mp4"), "<html>loi</html>");

        assertEquals(0, service.adoptExistingVideos(1L));
        assertEquals(JobStatus.PENDING, s1.getStatus());
    }

    @Test
    void sceneAlreadyInProgressIsNotTouched() throws IOException {
        JobEntity s1 = scene(1, 1, JobStatus.AWAITING_REVIEW);
        JobEntity s2 = scene(2, 2, JobStatus.GENERATING);
        mp4("EP06-S1-01.mp4");
        mp4("EP06-S1-02.mp4");

        assertEquals(0, service.adoptExistingVideos(1L));
        assertEquals(JobStatus.AWAITING_REVIEW, s1.getStatus());
        assertEquals(JobStatus.GENERATING, s2.getStatus());
    }

    @Test
    void noVideoFolder_nothingHappens() {
        scene(1, 1, JobStatus.PENDING);
        assertEquals(0, service.adoptExistingVideos(1L));
        verify(transitions, never()).move(any(), any(), any(), anyString());
    }
}
