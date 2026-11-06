package com.aifilm.automation.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptTagsBareIdsTest {

    private static final Set<String> KNOWN = Set.of("CHAR-CLARA-1", "CHAR-CLARA-2", "CHAR-CLARA-10");

    @Test
    void outfitVariantMentioningBaseImageWithoutAt_findsTheBaseImage() {
        String p = "OUTFIT VARIANT REFERENCE — the same person as the supplied reference image CHAR-CLARA-1, in the outfit \"2\".";
        assertEquals(List.of("CHAR-CLARA-1"), PromptTags.bareKnownIds(p, KNOWN, "CHAR-CLARA-2"));
    }

    @Test
    void doesNotConfuseCLARA1WithCLARA10_andIgnoresSelf() {
        String p = "based on CHAR-CLARA-10 and CHAR-CLARA-2 itself";
        assertEquals(List.of("CHAR-CLARA-10"), PromptTags.bareKnownIds(p, KNOWN, "CHAR-CLARA-2"));
    }

    @Test
    void unknownIdsAndAtTagsAreIgnored() {
        assertEquals(List.of(), PromptTags.bareKnownIds("use @CHAR-CLARA-1 and X-RAY-9", KNOWN, "CHAR-CLARA-2"));
    }
}
