package com.huanghuang.rsintegration.mods.rs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubstringCandidateIndexTest {

    @Test
    void narrowsChineseAndLatinSubstringQueries() {
        SubstringCandidateIndex index = new SubstringCandidateIndex();
        String apple = "item|minecraft:apple|-";
        String wool = "item|minecraft:red_wool|-";
        index.index(1, apple, "苹果 apple farmersdelight");
        index.index(1, wool, "红色羊毛 red wool minecraft");

        assertEquals(List.of(apple), index.candidates(1, "苹果"));
        assertEquals(List.of(apple), index.candidates(1, "apple"));
        assertEquals(List.of(wool), index.candidates(1, "羊毛"));
    }

    @Test
    void intersectsEveryTrigramBeforeReturningCandidates() {
        SubstringCandidateIndex index = new SubstringCandidateIndex();
        String exact = "item|minecraft:white_wool|-";
        String partial = "item|minecraft:oak_log|-";
        index.index(2, exact, "minecraft:wool");
        index.index(2, partial, "minecraft:wood");

        assertEquals(List.of(exact), index.candidates(2, "craft:wool"));
    }

    @Test
    void removedIdsAndClearedModesDoNotLeakCandidates() {
        SubstringCandidateIndex index = new SubstringCandidateIndex();
        String id = "item|example:thing|-";
        index.index(4, id, "examplemod");
        index.remove(id);
        assertEquals(List.of(), index.candidates(4, "example"));

        index.index(4, id, "examplemod");
        index.clearMode(4);
        assertEquals(List.of(), index.candidates(4, "example"));
    }

    @Test
    void newlyIndexedCandidatesBecomeVisibleWithoutRebuildingExistingEntries() {
        SubstringCandidateIndex index = new SubstringCandidateIndex();
        String first = "item|example:first|-";
        String second = "item|example:second|-";
        index.index(1, first, "诅咒 curse");
        assertEquals(List.of(first), index.candidates(1, "诅咒"));

        index.index(1, second, "诅咒之书 curse book");
        assertEquals(List.of(first, second), index.candidates(1, "诅咒"));
    }

    @Test
    void usesDirectScanUntilIndexIsReady() {
        assertEquals(false, SubstringCandidateIndex.shouldUseIndex(
                false, 10, 100, 60));
    }

    @Test
    void usesIndexOnlyWhenItActuallyNarrowsTheSearch() {
        assertEquals(true, SubstringCandidateIndex.shouldUseIndex(
                true, 60, 100, 60));
        assertEquals(false, SubstringCandidateIndex.shouldUseIndex(
                true, 61, 100, 60));
        assertEquals(false, SubstringCandidateIndex.shouldUseIndex(
                true, 0, 0, 60));
    }

    @Test
    void buildsAnIndependentSnapshotFromModeTexts() {
        String apple = "item|minecraft:apple|-";
        String wool = "item|minecraft:white_wool|-";
        SubstringCandidateIndex snapshot = SubstringCandidateIndex.build(Map.of(
                1, Map.of(apple, "苹果 apple"),
                2, Map.of(wool, "minecraft:wool")));

        assertEquals(List.of(apple), snapshot.candidates(1, "apple"));
        assertEquals(List.of(wool), snapshot.candidates(2, "wool"));
    }

    @Test
    void buildsOneModeWithoutCarryingOtherModePostings() {
        String tooltipItem = "item|example:tooltip|-";
        String tagItem = "item|example:tag|-";
        SubstringCandidateIndex tooltip = SubstringCandidateIndex.build(
                1, Map.of(tooltipItem, "curse zhou"));
        SubstringCandidateIndex tag = SubstringCandidateIndex.build(
                2, Map.of(tagItem, "minecraft:logs"));

        assertEquals(List.of(tooltipItem), tooltip.candidates(1, "curse"));
        assertEquals(List.of(), tooltip.candidates(2, "logs"));
        assertEquals(List.of(tagItem), tag.candidates(2, "logs"));
        assertEquals(List.of(), tag.candidates(1, "curse"));
    }
}
