package com.huanghuang.rsintegration.crafting;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateEngineTest {

    @Test
    void timedOutScoringTailUsesNeutralDefaults() {
        ResourceLocation scored = new ResourceLocation("example", "scored");
        ResourceLocation timedOut = new ResourceLocation("example", "timed_out");
        Map<ResourceLocation, Integer> scores = new HashMap<>();
        Map<ResourceLocation, Integer> availability = new HashMap<>();
        scores.put(scored, 20);
        availability.put(scored, 1);

        int comparison = assertDoesNotThrow(() -> CandidateEngine.compareCandidateIds(
                scored, timedOut, scores, availability));

        assertTrue(comparison < 0);
    }
}
