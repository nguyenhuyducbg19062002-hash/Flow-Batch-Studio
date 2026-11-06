package com.aifilm.automation.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectFolderScannerTest {

    @TempDir
    Path root;

    private Path file(String rel) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        return Files.write(p, new byte[]{1});
    }

    private long entries() throws IOException {
        try (Stream<Path> s = Files.list(root)) {
            return s.count();
        }
    }

    @Test
    void episodeFolderLikeTap5_existingFoldersAreAssigned() throws IOException {
        file("all-video-prompts.xlsx");
        file("reference-prompts.xlsx");
        file("Kịch Bản.docx");
        file("Ảnh tham chiếu/CHAR-A-1.png");
        file("Ảnh tham chiếu/CHAR-B-1.jpg");
        file("Ảnh tham chiếu/ghi-chu.txt");
        file("phim/tap-5_003.mp4");
        file("_ket-qua-sai/tap-5_001.jpg");
        file("_video-tu-flow (tai khoan 2)/01 - a.mp4");

        var s = ProjectFolderScanner.scan(root);

        assertEquals(root.resolve("Ảnh tham chiếu").toString(), s.imageDir());
        assertTrue(s.imageExists());
        assertEquals(2, s.imageCount());
        assertEquals(root.resolve("phim").toString(), s.videoDir());   // không nhầm sang _video-tu-flow
        assertTrue(s.videoExists());
        assertEquals(1, s.videoCount());
        assertEquals(root.toString(), s.sourceDir());
        assertEquals(List.of("all-video-prompts.xlsx", "reference-prompts.xlsx"), s.promptFiles());
        assertEquals(root.getFileName().toString(), s.name());
    }

    @Test
    void noSubfolders_proposesNewOnes_andCreatesNothing() throws IOException {
        file("all-video-prompts.xlsx");
        long before = entries();

        var s = ProjectFolderScanner.scan(root);

        assertEquals(root.resolve("Ảnh tham chiếu").toString(), s.imageDir());
        assertFalse(s.imageExists());
        assertEquals(root.resolve("phim").toString(), s.videoDir());
        assertFalse(s.videoExists());
        assertEquals(0, s.imageCount());
        assertEquals(before, entries());   // chỉ đề xuất, việc tạo thư mục là của lúc lưu phim
    }

    @Test
    void englishAndAccentVariants() throws IOException {
        file("References/a.png");
        file("Videos/a.mp4");
        var s = ProjectFolderScanner.scan(root);
        assertEquals(root.resolve("References").toString(), s.imageDir());
        assertEquals(root.resolve("Videos").toString(), s.videoDir());
        assertEquals("", s.sourceDir());   // không có file prompt quen thuộc
    }

    @Test
    void imageFolderIsNeverAlsoTheVideoFolder() throws IOException {
        file("anh-va-video/x.png");
        var s = ProjectFolderScanner.scan(root);
        assertEquals(root.resolve("anh-va-video").toString(), s.imageDir());
        assertFalse(s.videoExists());
        assertEquals(root.resolve("phim").toString(), s.videoDir());
    }

    @Test
    void blankDirs_areFoundInSourceIgnoringCaseAndAccents() throws IOException {
        file("ẢNH THAM CHIẾU/a.png");
        file("PHIM/a.mp4");
        var d = ProjectFolderScanner.resolveDirs(root.toString(), "", null);
        assertEquals(root.resolve("ẢNH THAM CHIẾU").toString(), d.imageDir());
        assertEquals(root.resolve("PHIM").toString(), d.videoDir());
    }

    @Test
    void blankDirs_underscoreAndMissingAccentsStillMatch() throws IOException {
        file("anh_tham_chieu/a.png");
        file("Video/a.mp4");
        var d = ProjectFolderScanner.resolveDirs(root.toString(), null, "  ");
        assertEquals(root.resolve("anh_tham_chieu").toString(), d.imageDir());
        assertEquals(root.resolve("Video").toString(), d.videoDir());
    }

    @Test
    void blankDirs_notFound_areProposedInsideSource() throws IOException {
        file("all-video-prompts.xlsx");
        long before = entries();
        var d = ProjectFolderScanner.resolveDirs(root.toString(), "", "");
        assertEquals(root.resolve("Ảnh tham chiếu").toString(), d.imageDir());
        assertEquals(root.resolve("phim").toString(), d.videoDir());
        assertEquals(before, entries());   // chưa tạo gì: ProjectService tạo khi lưu phim
    }

    @Test
    void explicitDirIsKept_onlyTheBlankOneIsResolved() throws IOException {
        file("Phim/a.mp4");
        var d = ProjectFolderScanner.resolveDirs(root.toString(), "  D:\\anh-cua-toi  ", "");
        assertEquals("D:\\anh-cua-toi", d.imageDir());
        assertEquals(root.resolve("Phim").toString(), d.videoDir());
    }

    @Test
    void blankDirWithoutSource_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProjectFolderScanner.resolveDirs("", "", ""));
        assertThrows(IllegalArgumentException.class, () -> ProjectFolderScanner.resolveDirs(null, "x", ""));
        // điền đủ cả hai thì không cần thư mục nguồn
        var d = ProjectFolderScanner.resolveDirs("", "A", "B");
        assertEquals("A", d.imageDir());
        assertEquals("B", d.videoDir());
    }

    @Test
    void foldersMerelyContainingAnh_areNotTheImageFolder() throws IOException {
        file("Thành phẩm/a.png");
        file("Cảnh quay/b.png");
        file("Doanh thu/c.xlsx");
        var s = ProjectFolderScanner.scan(root);
        assertFalse(s.imageExists());
        assertEquals(root.resolve("Ảnh tham chiếu").toString(), s.imageDir());
    }

    @Test
    void keywordAtWordStart_matches() throws IOException {
        file("Hình ảnh gốc/a.png");
        assertEquals(root.resolve("Hình ảnh gốc").toString(), ProjectFolderScanner.scan(root).imageDir());
        assertTrue(ProjectFolderScanner.hasWord("anh-cua-toi", "anh"));
        assertTrue(ProjectFolderScanner.hasWord("tham chiếu 2", "thamchieu"));
        assertFalse(ProjectFolderScanner.hasWord("Thanh", "anh"));
        assertFalse(ProjectFolderScanner.hasWord("Doanh thu", "anh"));
    }

    @Test
    void missingRootIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProjectFolderScanner.scan(root.resolve("khong-co")));
    }
}
