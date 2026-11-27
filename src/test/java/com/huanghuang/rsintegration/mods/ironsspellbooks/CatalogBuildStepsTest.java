package com.huanghuang.rsintegration.mods.ironsspellbooks;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogBuildStepsTest {
    @Test
    void nestedWorkYieldsAndKeepsRecipeOrder() {
        var steps = new CatalogBuildSteps();
        var observed = new ArrayList<String>();
        steps.then(() -> steps.each(List.of("a", "b").iterator(), group -> {
            steps.afterCurrent(() -> observed.add(group + " done"));
            steps.each(List.of("1", "2").iterator(), item -> observed.add(group + item));
        }));
        steps.then(() -> observed.add("complete"));

        var firstBudget = new AtomicInteger(3);
        assertFalse(steps.advance(() -> firstBudget.getAndDecrement() <= 0));
        assertEquals(List.of("a1"), observed);

        assertTrue(steps.advance(() -> false));
        assertEquals(List.of("a1", "a2", "a done", "b1", "b2", "b done", "complete"), observed);
    }
}
