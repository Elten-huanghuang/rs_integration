package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactOutputIndexTest {
    @Test
    void hashCollisionsKeepDistinctItemsAndOriginalVariantOrder() {
        ResourceLocation firstId = new ResourceLocation("test", "collision_an");
        ResourceLocation secondId = new ResourceLocation("test", "collision_c0");
        assertEquals(firstId.hashCode(), secondId.hashCode());
        MaterialRef first = new MaterialRef(firstId, "{variant:1}");
        MaterialRef second = new MaterialRef(secondId, "");
        MaterialRef third = new MaterialRef(firstId, "{variant:2}");
        CompactOutputIndex index = new CompactOutputIndex(new LinkedHashSet<>(List.of(first, second, third)));
        assertEquals(List.of(first, third), index.find(firstId));
        assertEquals(List.of(second), index.find(secondId));
        assertTrue(index.find(new ResourceLocation("test", "missing")).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> index.find(firstId).clear());
    }

    @Test
    void emptyIndexAndInterruptedLookupAreHandled() {
        ResourceLocation itemId = new ResourceLocation("test", "item");
        assertTrue(new CompactOutputIndex(Set.of()).find(itemId).isEmpty());
        CompactOutputIndex index = new CompactOutputIndex(Set.of(new MaterialRef(itemId, "")));
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> index.find(itemId));
        } finally {
            Thread.interrupted();
        }
    }
}
