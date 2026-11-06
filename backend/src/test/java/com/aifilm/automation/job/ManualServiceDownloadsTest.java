package com.aifilm.automation.job;

import com.aifilm.automation.prompt.PromptKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManualServiceDownloadsTest {

    @TempDir
    Path dir;

    private void file(String name, long modified) throws Exception {
        Path f = dir.resolve(name);
        Files.write(f, new byte[200]);
        Files.setLastModifiedTime(f, FileTime.fromMillis(modified));
    }

    @Test
    void chiLayFileDungLoaiMoiHonMocVaDaGhiXong() throws Exception {
        long now = 1_000_000_000L;
        file("cu.mp4", now - 60_000);          // trước lúc bắt đầu cảnh
        file("moi.mp4", now - 10_000);
        file("moi-hon.mp4", now - 5_000);
        file("dang-ghi.mp4", now - 200);       // trình duyệt còn đang ghi
        file("anh.png", now - 5_000);           // sai loại cho cảnh video
        file("tai-do.mp4.crdownload", now - 5_000);

        List<ManualService.DownloadFile> out = ManualService.downloads(dir, now - 30_000, PromptKind.VIDEO, now);

        assertThat(out).extracting(ManualService.DownloadFile::name).containsExactly("moi-hon.mp4", "moi.mp4");
    }

    @Test
    void canhAnhChiNhanAnh() throws Exception {
        long now = 1_000_000_000L;
        file("a.png", now - 5_000);
        file("b.mp4", now - 5_000);

        assertThat(ManualService.downloads(dir, 0, PromptKind.IMAGE, now))
                .extracting(ManualService.DownloadFile::name).containsExactly("a.png");
    }

    @Test
    void thuMucKhongTonTaiTraVeRong() {
        assertThat(ManualService.downloads(dir.resolve("khong-co"), 0, PromptKind.VIDEO, 0)).isEmpty();
    }
}
