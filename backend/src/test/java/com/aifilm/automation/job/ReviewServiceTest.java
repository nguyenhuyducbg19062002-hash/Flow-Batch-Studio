package com.aifilm.automation.job;

import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.project.ProjectRepository;
import com.aifilm.automation.prompt.ProjectAssetRepository;
import com.aifilm.automation.prompt.PromptEntity;
import com.aifilm.automation.prompt.PromptKind;
import com.aifilm.automation.prompt.PromptRepository;
import com.aifilm.automation.prompt.PromptRevisionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewServiceTest {

    @TempDir
    Path tmp;

    private JobRepository jobs;
    private ReviewService service;
    private JobEntity job;
    private Path imageDir;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("afm.data.dir", tmp.resolve("data").toString());
        imageDir = tmp.resolve("images");
        jobs = mock(JobRepository.class);
        PromptRepository prompts = mock(PromptRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        PromptRevisionRepository revisions = mock(PromptRevisionRepository.class);
        ProjectAssetRepository assets = mock(ProjectAssetRepository.class);
        service = new ReviewService(jobs, prompts, revisions, projects, assets,
                new JobTransitions(mock(JobEventRepository.class)), mock(AccountRepository.class));

        ProjectEntity p = new ProjectEntity();
        p.setId(1L);
        p.setCode("phim");
        p.setImageDir(imageDir.toString());
        when(projects.findById(1L)).thenReturn(Optional.of(p));
        PromptEntity prompt = new PromptEntity();
        prompt.setId(5L);
        prompt.setProjectId(1L);
        prompt.setKind(PromptKind.IMAGE);
        prompt.setExternalId("CHAR-A");
        when(prompts.findById(5L)).thenReturn(Optional.of(prompt));
        job = new JobEntity(1L, 5L, PromptKind.IMAGE);
        job.setId(9L);
        when(jobs.findById(9L)).thenReturn(Optional.of(job));
        when(jobs.findByProjectId(1L)).thenReturn(List.of(job));
        when(assets.findByProjectIdAndExternalId(anyLong(), org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("afm.data.dir");
    }

    private static byte[] png(int fill) {
        byte[] b = new byte[200];
        Arrays.fill(b, (byte) fill);
        b[0] = (byte) 0x89;
        b[1] = 'P';
        b[2] = 'N';
        b[3] = 'G';
        return b;
    }

    private void awaitingReview(byte[] content) throws Exception {
        Path staged = tmp.resolve("data").resolve("staging").resolve("job-9").resolve("out.png");
        Files.createDirectories(staged.getParent());
        Files.write(staged, content);
        job.setPreviewPath(staged.toString());
        job.setStatus(JobStatus.AWAITING_REVIEW);
    }

    private List<String> imageFiles() throws Exception {
        try (var s = Files.list(imageDir)) {
            return s.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void approve_userFileWithSameName_keptAsBak() throws Exception {
        Files.createDirectories(imageDir);
        Files.write(imageDir.resolve("CHAR-A.png"), png(1));
        awaitingReview(png(2));

        service.approve(9L);

        assertArrayEquals(png(2), Files.readAllBytes(imageDir.resolve("CHAR-A.png")));
        List<String> files = imageFiles();
        assertEquals(2, files.size());
        assertTrue(files.get(1).startsWith("CHAR-A.png.") && files.get(1).endsWith(".bak"));
        assertEquals(JobStatus.SUCCESS, job.getStatus());
    }

    @Test
    void approve_ownPreviousFile_overwrittenWithoutBak() throws Exception {
        Files.createDirectories(imageDir);
        Files.write(imageDir.resolve("CHAR-A.png"), png(1));
        job.setFinalPath(imageDir.resolve("CHAR-A.png").toString());
        awaitingReview(png(2));

        service.approve(9L);

        assertEquals(List.of("CHAR-A.png"), imageFiles());
    }

    @Test
    void manualResult_brokenFile_doesNotReplaceOldPreview() throws Exception {
        Path old = tmp.resolve("data").resolve("staging").resolve("job-9").resolve("manual").resolve("CHAR-A.png");
        Files.createDirectories(old.getParent());
        Files.write(old, png(3));
        job.setStatus(JobStatus.FAILED);

        assertThrows(IllegalArgumentException.class,
                () -> service.manualResult(9L, new MockMultipartFile("file", "x.png", "image/png", "not an image".getBytes())));

        assertArrayEquals(png(3), Files.readAllBytes(old));
        try (var s = Files.list(old.getParent())) {
            assertEquals(1, s.count());
        }
    }

    @Test
    void regenerate_onPending_doesNotThrow() {
        assertEquals(JobStatus.PENDING, service.regenerate(9L, null, null).getStatus());
    }

    @Test
    void cancel_onSuccessOrCancelled_isNoop() {
        job.setStatus(JobStatus.SUCCESS);
        assertEquals(JobStatus.SUCCESS, service.cancel(9L).getStatus());
        job.setStatus(JobStatus.CANCELLED);
        assertEquals(JobStatus.CANCELLED, service.cancel(9L).getStatus());
    }

    @Test
    void runSelected_nothingPicked_keepsOldSelection() {
        job.setStatus(JobStatus.GENERATING);
        job.setRunPriority(3);

        assertEquals(0, service.runSelected(1L, List.of(9L), true, false).queued());

        assertEquals(3, job.getRunPriority());
    }
}
