package com.aifilm.automation.job;

import com.aifilm.automation.prompt.PromptEntity;
import com.aifilm.automation.prompt.PromptKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BatchServiceTest {

    @Test
    void parseNumbers_acceptsListsRangesFileNamesAndSpaces() {
        assertEquals(List.of(2, 5, 6, 12, 30, 31, 32, 33),
                List.copyOf(BatchService.parseNumbers("5, 12; 030-033 2 6 005.png 5 - 6")));
        assertEquals(List.of(3, 4, 5), List.copyOf(BatchService.parseNumbers("5–3")));
    }

    @Test
    void parseNumbers_rejectsGarbageAndEmpty() {
        assertThrows(IllegalArgumentException.class, () -> BatchService.parseNumbers("5, abc"));
        assertThrows(IllegalArgumentException.class, () -> BatchService.parseNumbers("  "));
    }

    @Test
    void numbering_followsFileOrderThenRow_andPadsToAtLeastThreeDigits() {
        PromptEntity panel = prompt(10L, PromptKind.IMAGE, 2, 1);   // file Excel thứ 2
        PromptEntity b = prompt(11L, PromptKind.IMAGE, 1, 2);
        PromptEntity a = prompt(12L, PromptKind.IMAGE, 1, 1);
        PromptEntity video = prompt(13L, PromptKind.VIDEO, 1, 1);
        Map<Long, Integer> n = BatchNumbering.numbers(List.of(panel, b, a, video), PromptKind.IMAGE);
        assertEquals(Map.of(12L, 1, 11L, 2, 10L, 3), n);
        assertEquals("007", BatchNumbering.baseName(7, 94));
        assertEquals("0042", BatchNumbering.baseName(42, 1200));
    }

    private static PromptEntity prompt(long id, PromptKind kind, int sourceOrder, int seq) {
        PromptEntity p = new PromptEntity();
        p.setId(id);
        p.setKind(kind);
        p.setSourceOrder(sourceOrder);
        p.setSeqNo(seq);
        return p;
    }
}
