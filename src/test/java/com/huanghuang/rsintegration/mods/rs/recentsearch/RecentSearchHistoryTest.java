package com.huanghuang.rsintegration.mods.rs.recentsearch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentSearchHistoryTest {
    @Test
    void recordsMostRecentFirstAndPreservesFavoriteOnDuplicate() {
        RecentSearchHistory history = new RecentSearchHistory(10);
        history.record("stone");
        history.toggleFavorite("stone");
        history.record("iron");
        history.record(" stone ");

        assertEquals(List.of(
                new RecentSearchEntry("stone", true),
                new RecentSearchEntry("iron", false)
        ), history.entries());
    }

    @Test
    void visibleEntriesGroupFavoritesWithoutChangingStoredMruOrder() {
        RecentSearchHistory history = new RecentSearchHistory(10);
        history.replaceAll(List.of(
                new RecentSearchEntry("new", false),
                new RecentSearchEntry("pinned", true),
                new RecentSearchEntry("old", false)
        ));

        assertEquals(List.of(
                new RecentSearchEntry("pinned", true),
                new RecentSearchEntry("new", false)
        ), history.visibleEntries(2));
        assertEquals("new", history.entries().get(0).query());
    }

    @Test
    void visibleEntriesDoNotExceedLimitWhenFavoritesFillIt() {
        RecentSearchHistory history = new RecentSearchHistory(10);
        history.replaceAll(List.of(
                new RecentSearchEntry("pinned-one", true),
                new RecentSearchEntry("pinned-two", true),
                new RecentSearchEntry("ordinary", false)
        ));

        assertEquals(List.of(
                new RecentSearchEntry("pinned-one", true),
                new RecentSearchEntry("pinned-two", true)
        ), history.visibleEntries(2));
    }

    @Test
    void capacityEvictsOldestNonFavoriteFirst() {
        RecentSearchHistory history = new RecentSearchHistory(3);
        history.replaceAll(List.of(
                new RecentSearchEntry("pinned", true),
                new RecentSearchEntry("old", false),
                new RecentSearchEntry("older", false)
        ));

        history.record("new");

        assertEquals(List.of(
                new RecentSearchEntry("new", false),
                new RecentSearchEntry("pinned", true),
                new RecentSearchEntry("old", false)
        ), history.entries());
    }

    @Test
    void newNonFavoriteIsRejectedWhenCapacityContainsOnlyFavorites() {
        RecentSearchHistory history = new RecentSearchHistory(2);
        history.replaceAll(List.of(
                new RecentSearchEntry("one", true),
                new RecentSearchEntry("two", true)
        ));

        assertFalse(history.record("three"));
        assertEquals(List.of(
                new RecentSearchEntry("one", true),
                new RecentSearchEntry("two", true)
        ), history.entries());
    }

    @Test
    void removeAndClearReportWhetherStateChanged() {
        RecentSearchHistory history = new RecentSearchHistory(2);
        history.record("stone");

        assertFalse(history.remove("iron"));
        assertTrue(history.remove("stone"));
        assertFalse(history.clear());
        history.record("gold");
        assertTrue(history.clear());
    }
}
