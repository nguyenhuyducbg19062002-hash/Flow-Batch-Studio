package com.aifilm.automation.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssetImageFilesTest {

    @TempDir
    Path root;

    private void file(String rel) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[]{1});
    }

    private List<String> names(Path dir) {
        return AssetService.imageFiles(dir).stream().map(p -> dir.relativize(p).toString().replace('\\', '/')).toList();
    }

    @Test
    void sideFoldersAreSkipped_butOrdinarySubfoldersAreScanned() throws IOException {
        file("Ảnh tham chiếu/CHAR-A-1.png");
        file("Ảnh tham chiếu/Panels/PANEL-3.jpg");
        file("Ảnh tham chiếu/_ket-qua-sai/PANEL-1.jpg");      // ảnh sai người dùng đã loại: không được nạp
        file("Ảnh tham chiếu/_ket-qua-sai/sau/PANEL-2.jpg");
        file("Ảnh tham chiếu/.cache/x.png");
        file("Ảnh tham chiếu/ghi-chu.txt");
        assertEquals(List.of("Ảnh tham chiếu/CHAR-A-1.png", "Ảnh tham chiếu/Panels/PANEL-3.jpg"), names(root));
    }

    @Test
    void chosenFolderItselfIsAlwaysScanned_evenIfItsNameStartsWithUnderscore() throws IOException {
        file("_ket-qua-sai/PANEL-1.jpg");
        file("_ket-qua-sai/_con/PANEL-2.jpg");
        Path chosen = root.resolve("_ket-qua-sai");
        assertEquals(List.of("PANEL-1.jpg"), names(chosen));
    }
}
