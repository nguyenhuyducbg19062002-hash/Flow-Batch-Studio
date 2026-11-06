package com.aifilm.automation.project;

import com.aifilm.automation.job.JobRepository;
import com.aifilm.automation.job.JobTransitions;
import com.aifilm.automation.prompt.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OmniImportServiceParseTest {

    @TempDir
    Path tmp;

    private PromptRepository prompts;
    private AssetService assetService;
    private OmniImportService service;

    @BeforeEach
    void setUp() {
        ProjectRepository projects = mock(ProjectRepository.class);
        prompts = mock(PromptRepository.class);
        assetService = mock(AssetService.class);
        PromptRevisionRepository revisions = mock(PromptRevisionRepository.class);
        when(revisions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new OmniImportService(projects, prompts, revisions, mock(PromptReferenceRepository.class),
                mock(ProjectAssetRepository.class), mock(JobRepository.class), mock(JobTransitions.class), TestFiles.directTx(), assetService);
        ProjectEntity p = new ProjectEntity();
        p.setId(1L);
        p.setCode("phim");
        p.setImageDir(tmp.resolve("anh").toString());
        when(projects.findById(1L)).thenReturn(Optional.of(p));
        when(assetService.importFolder(anyLong(), anyString(), any()))
                .thenReturn(new AssetService.FolderImportResult(0, 0, 0, 0, List.of(), List.of()));
    }

    private Path write(String rel, String... lines) throws IOException {
        Path p = tmp.resolve(rel);
        Files.createDirectories(p.getParent());
        return Files.write(p, List.of(lines), StandardCharsets.UTF_8);
    }

    @Test
    void formulaCells_areReadAsTheirComputedValue() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet();
            var h = sheet.createRow(0);
            h.createCell(0).setCellValue("Reference ID");
            h.createCell(1).setCellValue("Prompt tạo reference");
            var r = sheet.createRow(1);
            r.createCell(0).setCellFormula("\"CHAR-\"&\"A-1\"");
            r.createCell(1).setCellFormula("CONCATENATE(\"Hello \",\"world\")");
            wb.write(out);
        }
        var rows = service.readImages(new ByteArrayInputStream(out.toByteArray()), "reference-prompts.xlsx");
        assertEquals(1, rows.size());
        assertEquals("CHAR-A-1", rows.get(0).id());
        assertEquals("Hello world", rows.get(0).prompt());
    }

    @Test
    void referenceTxt_idsByNumber_countMustMatch() throws IOException {
        Path f = write("reference-prompts.txt", "2. prompt hai", "1. prompt mot");
        write("reference-id-list.txt", "1. CHAR-A-1", "2. CHAR-B-1");

        var rows = OmniImportService.txtLines(PromptKind.IMAGE, f);
        assertEquals(List.of("CHAR-B-1", "CHAR-A-1"), rows.stream().map(OmniImportService.Line::id).toList());

        write("reference-id-list.txt", "1. CHAR-A-1", "2. CHAR-B-1", "3. CHAR-C-1");
        var e = assertThrows(IllegalArgumentException.class, () -> OmniImportService.txtLines(PromptKind.IMAGE, f));
        assertTrue(e.getMessage().contains("3 ID"));
    }

    @Test
    void manualImageText_onlyUppercaseHyphenIdsCountAsIds() {
        var rows = service.textLines(1L, PromptKind.IMAGE, "Wide-angle: a street at night\nCHAR-A-1: a man\nloc-2 | a room");
        assertEquals(List.of("ANH-1", "CHAR-A-1", "ANH-2"), rows.stream().map(OmniImportService.Line::id).toList());
        assertEquals("Wide-angle: a street at night", rows.get(0).prompt());
        assertEquals("a man", rows.get(1).prompt());
    }

    @Test
    void folderTxt_onlyKnownNames_referenceBeforePanel() throws IOException {
        write("ghi-chu.txt", "KHONG-PHAI-PROMPT: ghi chú");
        write("PROMPT-ANH-PANEL.txt", "1. panel prompt");
        write("reference-prompts.txt", "1. ref prompt");
        write("reference-id-list.txt", "1. CHAR-A-1");

        service.importFolder(1L, tmp.toString());

        ArgumentCaptor<PromptEntity> saved = ArgumentCaptor.forClass(PromptEntity.class);
        verify(prompts, atLeastOnce()).save(saved.capture());
        assertEquals(List.of("CHAR-A-1", "PANEL-1"), saved.getAllValues().stream().map(PromptEntity::getExternalId).toList());
    }

    @Test
    void imageGroups_duplicateNamesGetSuffix_rerunReusesSceneNumbers() throws IOException {
        TestFiles.png(tmp.resolve("nhom/g1/1.png"));
        TestFiles.png(tmp.resolve("nhom/g1/1.jpg"));
        write("nhom/g1.txt", "prompt chung");

        service.importImageGroups(1L, tmp.resolve("nhom").toString(), false);
        verify(assetService).upsert(eq(1L), eq("g1-1"), any(), eq("USER"));
        verify(assetService).upsert(eq(1L), eq("g1-1-2"), any(), eq("USER"));
        verify(prompts).findByProjectIdAndKindAndExternalId(1L, PromptKind.VIDEO, "001");
        verify(prompts).findByProjectIdAndKindAndExternalId(1L, PromptKind.VIDEO, "002");

        // chạy lại: hai cảnh đã có từ thư mục này giữ STT 001/002, không thành 003/004
        PromptEntity a = videoPrompt(1, "Ảnh→Video: nhom");
        PromptEntity b = videoPrompt(2, "Ảnh→Video: nhom");
        when(prompts.findByProjectId(1L)).thenReturn(List.of(a, b));
        service.importImageGroups(1L, tmp.resolve("nhom").toString(), false);
        verify(prompts, never()).findByProjectIdAndKindAndExternalId(1L, PromptKind.VIDEO, "003");
    }

    private static PromptEntity videoPrompt(int seq, String source) {
        PromptEntity p = new PromptEntity();
        p.setKind(PromptKind.VIDEO);
        p.setExternalId(String.format("%03d", seq));
        p.setSeqNo(seq);
        p.setSourceName(source);
        return p;
    }
}
