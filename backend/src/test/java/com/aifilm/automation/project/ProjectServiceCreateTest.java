package com.aifilm.automation.project;

import com.aifilm.automation.account.AccountRepository;
import com.aifilm.automation.job.JobEventRepository;
import com.aifilm.automation.job.JobRepository;
import com.aifilm.automation.job.JobTransitions;
import com.aifilm.automation.prompt.ProjectAssetRepository;
import com.aifilm.automation.prompt.PromptReferenceRepository;
import com.aifilm.automation.prompt.PromptRepository;
import com.aifilm.automation.prompt.PromptRevisionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectServiceCreateTest {

    @TempDir
    Path tmp;

    @Test
    void duplicateCode_isRejectedBeforeAnyFolderIsCreated() {
        ProjectRepository projects = mock(ProjectRepository.class);
        when(projects.findByCode("phim-a")).thenReturn(Optional.of(new ProjectEntity()));
        ProjectService service = new ProjectService(projects, mock(JobRepository.class), mock(JobEventRepository.class),
                mock(PromptRepository.class), mock(PromptRevisionRepository.class), mock(PromptReferenceRepository.class),
                mock(AccountRepository.class), mock(ProjectAssetRepository.class), mock(JobTransitions.class),
                mock(OmniImportService.class), mock(AssetService.class), mock(FlowLinkRepository.class));
        Path img = tmp.resolve("anh");
        Path vid = tmp.resolve("phim");
        var form = new ProjectService.ProjectForm("Phim A", null, "", img.toString(), vid.toString(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        var e = assertThrows(IllegalArgumentException.class, () -> service.create(form));

        assertTrue(e.getMessage().contains("đã tồn tại"));
        assertFalse(Files.exists(img));
        assertFalse(Files.exists(vid));
    }

    @Test
    void imageDirInsideSource_isScannedWithSource_unlessUnderSideFolder() {
        Path src = tmp.resolve("Omni");
        assertTrue(ProjectService.scannedWith(src, src));
        assertTrue(ProjectService.scannedWith(src, src.resolve("Ảnh tham chiếu")));
        assertFalse(ProjectService.scannedWith(src, src.resolve("_ket-qua/anh")));   // quét thư mục cha sẽ bỏ qua "_…"
        assertFalse(ProjectService.scannedWith(src, src.resolve(".cache")));
        assertFalse(ProjectService.scannedWith(src, tmp.resolve("noi-khac")));
        assertFalse(ProjectService.scannedWith(null, src));
    }
}
