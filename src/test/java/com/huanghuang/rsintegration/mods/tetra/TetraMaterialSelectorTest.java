package com.huanghuang.rsintegration.mods.tetra;

import com.huanghuang.rsintegration.mods.tetra.client.TetraMaterialSelector;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TetraMaterialSelectorTest {
    @Test
    void matchesMaterialPrefixesAndCategories() {
        Set<String> selectors = Set.of("tetra:metal/", "gem");

        assertTrue(TetraMaterialSelector.matches("tetra:metal/iron", "metal", selectors));
        assertTrue(TetraMaterialSelector.matches("tetra:gem/diamond", "gem", selectors));
        assertFalse(TetraMaterialSelector.matches("tetra:wood/oak", "wood", selectors));
    }

    @Test
    void matchesTetraApplicableMaterialSyntax() {
        Set<String> selectors = Set.of("#wood", "!iron");

        assertTrue(TetraMaterialSelector.matches("oak", "wood", selectors));
        assertTrue(TetraMaterialSelector.matches("iron", "metal", selectors));
        assertFalse(TetraMaterialSelector.matches("copper", "metal", selectors));
    }

    @Test
    void doesNotMatchUnrelatedMaterialWhenSelectorIsEmpty() {
        assertFalse(TetraMaterialSelector.matches("tetra:metal/iron", "metal", Set.of()));
    }
}
