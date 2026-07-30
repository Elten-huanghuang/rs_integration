package com.huanghuang.rsintegration.crafting.plan;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaxCraftableSearchTest {

    @Test
    void findsMaximumAboveCurrentCount() {
        assertEquals(37, runSearch(1024, 1, true, count -> count <= 37));
    }

    @Test
    void findsMaximumBelowCurrentCount() {
        assertEquals(12, runSearch(1024, 64, false, count -> count <= 12));
    }

    @Test
    void handlesNothingCraftableAndConfiguredLimit() {
        assertEquals(0, runSearch(1024, 1, false, count -> false));
        assertEquals(1024, runSearch(1024, 1, true, count -> true));
    }

    @Test
    void searchesEntireRangeWithoutAClientStartingPoint() {
        MaxCraftableSearch search = new MaxCraftableSearch(1024);
        OptionalInt probe;
        while ((probe = search.nextProbe()).isPresent()) {
            int candidate = probe.getAsInt();
            search.accept(candidate, candidate <= 73);
        }
        assertEquals(73, search.result());
    }

    private static int runSearch(int limit, int current, boolean currentFeasible,
                                 IntPredicate feasibility) {
        MaxCraftableSearch search = new MaxCraftableSearch(limit, current, currentFeasible);
        OptionalInt probe;
        while ((probe = search.nextProbe()).isPresent()) {
            int candidate = probe.getAsInt();
            search.accept(candidate, feasibility.test(candidate));
        }
        return search.result();
    }
}
