package com.aifilm.automation.project;

import com.aifilm.automation.prompt.PromptKind;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProjectControllerTest {

    @Test
    void missingPath_isAVietnameseBadRequest_notANullPointer() {
        assertEquals("Chưa chọn thư mục", assertThrows(IllegalArgumentException.class, () -> ProjectController.pathOf(null)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> ProjectController.pathOf(Map.of()));
        Map<String, Object> blank = new HashMap<>();
        blank.put("path", "  ");
        assertThrows(IllegalArgumentException.class, () -> ProjectController.pathOf(blank));
        assertEquals("D:\\x", ProjectController.pathOf(Map.of("path", " D:\\x ")));
    }

    @Test
    void wrongKind_isAClearBadRequest() {
        assertEquals(PromptKind.IMAGE, ProjectController.kindOf("image"));
        assertEquals(PromptKind.VIDEO, ProjectController.kindOf(" VIDEO "));
        var e = assertThrows(IllegalArgumentException.class, () -> ProjectController.kindOf("anh"));
        assertTrue(e.getMessage().contains("IMAGE") && e.getMessage().contains("anh"));
        assertThrows(IllegalArgumentException.class, () -> ProjectController.kindOf(null));
    }
}
