package com.aifilm.automation.job;

import com.aifilm.automation.project.ProjectEntity;
import com.aifilm.automation.prompt.PromptEntity;
import com.aifilm.automation.prompt.PromptKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoNamingTest {

    private static long nextId = 1;

    private static PromptEntity video(int stt, String summary) {
        PromptEntity p = new PromptEntity();
        p.setId(nextId++);
        p.setKind(PromptKind.VIDEO);
        p.setExternalId(String.format("%03d", stt));
        p.setSeqNo(stt);
        p.setSourceOrder(0);
        p.setSummaryVi(summary);
        return p;
    }

    private static String scene(String name) {
        return "Cảnh " + name + "\n1. Cửa đóng, Clara mở cửa bước vào";
    }

    /** Cảnh EP06-S1 có 24 video (STT 1–24), EP06-S2 có 16 video (STT 25–40). */
    private static List<PromptEntity> episode() {
        List<PromptEntity> all = new ArrayList<>();
        for (int i = 1; i <= 24; i++) all.add(video(i, scene("EP06-S1")));
        for (int i = 25; i <= 40; i++) all.add(video(i, scene("EP06-S2")));
        return all;
    }

    private static String name(PromptEntity p, List<PromptEntity> all) {
        return VideoNaming.nameFor(p, all).orElseThrow();
    }

    @Test
    void sceneNameComesFromTheFirstLineOfTheNote() {
        assertEquals(Optional.of("EP06-S1"), VideoNaming.sceneOf("Cảnh EP06-S1\n1. Cửa đóng"));
        assertEquals(Optional.of("EP06-S103"), VideoNaming.sceneOf("Cảnh EP06-S103\n1. Ethan âu yếm"));
        assertEquals(Optional.of("EP06-S1"), VideoNaming.sceneOf("Cảnh EP06-S1.\nmô tả"));
        assertEquals(Optional.of("ep06-s3"), VideoNaming.sceneOf("canh ep06-s3 mô tả"));
        assertEquals(Optional.of("EP06-S2"), VideoNaming.sceneOf("Cảnh EP06-S2\n1. dấu ở dạng tách rời"));
        assertEquals(Optional.empty(), VideoNaming.sceneOf("1. Cửa đóng, Clara mở cửa"));
        assertEquals(Optional.empty(), VideoNaming.sceneOf(null));
        assertEquals(Optional.empty(), VideoNaming.sceneOf("   "));
    }

    @Test
    void videosAreNumberedInsideTheirSceneInFileOrder() {
        List<PromptEntity> all = episode();
        assertEquals("EP06-S1-01", name(all.get(0), all));
        assertEquals("EP06-S1-02", name(all.get(1), all));
        assertEquals("EP06-S1-24", name(all.get(23), all));
        assertEquals("EP06-S2-01", name(all.get(24), all));   // sang cảnh mới thì đếm lại từ 1
        assertEquals("EP06-S2-16", name(all.get(39), all));
    }

    @Test
    void numberingDoesNotDependOnTheOrderOfTheList() {
        List<PromptEntity> all = episode();
        List<PromptEntity> shuffled = new ArrayList<>(all);
        Collections.reverse(shuffled);
        for (PromptEntity p : all) {
            assertEquals(name(p, all), name(p, shuffled));
        }
    }

    @Test
    void allNamesAreUniqueAndSortByFileName() {
        List<PromptEntity> all = episode();
        List<String> names = all.stream().map(p -> name(p, all)).toList();
        assertEquals(names.size(), names.stream().distinct().count());
        assertEquals(names, names.stream().sorted().toList(), "tên sắp theo bảng chữ cái cũng đúng thứ tự file Excel");
    }

    @Test
    void aSceneWithAHundredOrMoreVideosUsesThreeDigits() {
        List<PromptEntity> all = new ArrayList<>();
        for (int i = 1; i <= 120; i++) all.add(video(i, scene("EP06-S9")));
        assertEquals("EP06-S9-001", name(all.get(0), all));
        assertEquals("EP06-S9-120", name(all.get(119), all));
    }

    @Test
    void aSceneThatAppearsInTwoPlacesIsCountedTogetherInFileOrder() {
        List<PromptEntity> all = new ArrayList<>();
        all.add(video(1, scene("EP06-S1")));
        all.add(video(2, scene("EP06-S2")));
        all.add(video(3, scene("EP06-S1")));
        assertEquals("EP06-S1-02", name(all.get(2), all));
        assertEquals("EP06-S2-01", name(all.get(1), all));
    }

    @Test
    void noSceneInTheNote_fallsBackToTheOldNaming() {
        ProjectEntity project = new ProjectEntity();
        project.setCode("tap-5-ha-cupid");
        List<PromptEntity> all = List.of(video(42, "mô tả không có tên cảnh"));
        assertTrue(VideoNaming.nameFor(all.get(0), all).isEmpty());
        assertEquals("tap-5-ha-cupid_042.mp4", ReviewService.finalFileName(project, all.get(0), ".mp4", all));
    }

    @Test
    void finalFileName_videoUsesTheSceneName_imageUsesTheReferenceId() {
        ProjectEntity project = new ProjectEntity();
        project.setCode("kb-ha-tap-6");
        List<PromptEntity> all = episode();
        assertEquals("EP06-S1-03.mp4", ReviewService.finalFileName(project, all.get(2), ".mp4", all));

        PromptEntity image = new PromptEntity();
        image.setKind(PromptKind.IMAGE);
        image.setExternalId("CHAR-CLARA-1");
        assertEquals("CHAR-CLARA-1.jpg", ReviewService.finalFileName(project, image, ".jpg", List.of()));
    }
}
