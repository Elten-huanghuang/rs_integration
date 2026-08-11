package com.huanghuang.rsintegration.mods.rs;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgressiveSearchResultsTest {
    @Test
    void partialScanAddsToAlreadyPublishedSeed() {
        assertEquals(Set.of("seed", "scanned"), ProgressiveSearchResults.merge(
                Set.of("seed"), 4L, Set.of("scanned"), 4L, false));
    }

    @Test
    void completedScanReplacesSeedWithAuthoritativeResults() {
        assertEquals(Set.of("scanned"), ProgressiveSearchResults.merge(
                Set.of("seed"), 4L, Set.of("scanned"), 4L, true));
    }

    @Test
    void newIndexVersionDoesNotReuseStalePartialResults() {
        assertEquals(Set.of("fresh"), ProgressiveSearchResults.merge(
                Set.of("stale"), 3L, Set.of("fresh"), 4L, false));
    }
}
