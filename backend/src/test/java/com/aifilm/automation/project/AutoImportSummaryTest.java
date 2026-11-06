package com.aifilm.automation.project;

import com.aifilm.automation.project.OmniImportService.FolderResult;
import com.aifilm.automation.project.OmniImportService.ImportResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoImportSummaryTest {

    private static ImportResult file(String name, String kind, int rows, List<String> missing) {
        return new ImportResult(name, kind, rows, rows, 0, 0, 0, missing, 0, 0, List.of());
    }

    @Test
    void summarizesFilesAndImages_missingComesFromCurrentStateNotFromLastFile() {
        var r = new FolderResult(
                List.of(file("reference-prompts.xlsx", "IMAGE", 40, List.of()),
                        file("all-video-prompts.xlsx", "VIDEO", 556, List.of("CHAR-X-1", "LOC-9"))),
                new AssetService.FolderImportResult(95, 4, 2, 99, List.of(), List.of("a.png: trùng ID LOC-9 với b.png, bỏ qua")), List.of());
        var lines = AutoImportSummary.lines(r);
        assertEquals("✓ reference-prompts.xlsx: 40 ảnh", lines.get(0));
        assertEquals("✓ all-video-prompts.xlsx: 556 video", lines.get(1));
        assertEquals("✓ 99 ảnh có sẵn đã gán vào phim (99 ảnh khỏi phải tạo)", lines.get(2));
        assertEquals("⚠ a.png: trùng ID LOC-9 với b.png, bỏ qua", lines.get(3));
        assertEquals(4, lines.size());   // "Còn thiếu" không lấy từ file cuối (ảnh gán sau đó có thể đã bù)
    }

    @Test
    void missingLine_fromCurrentMissingRefs() {
        assertTrue(AutoImportSummary.missing(List.of()).isEmpty());
        var line = AutoImportSummary.missing(List.of("A", "B", "C", "D", "E", "F", "G")).orElseThrow();
        assertTrue(line.contains("Còn thiếu 7 ảnh gốc") && line.endsWith("A, B, C, D, E…"));
        assertTrue(AutoImportSummary.missing(List.of("CHAR-X-1", "LOC-9")).orElseThrow().contains("CHAR-X-1, LOC-9"));
    }

    @Test
    void nothingToReport_whenNothingImported() {
        var r = new FolderResult(List.of(), new AssetService.FolderImportResult(0, 0, 0, 0, List.of(), List.of()), List.of());
        assertTrue(AutoImportSummary.lines(r).isEmpty());
    }
}
