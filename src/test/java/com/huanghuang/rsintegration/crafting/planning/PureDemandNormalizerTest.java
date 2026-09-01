package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PureDemandNormalizerTest {
    @Test
    void preservesModeWhenMergingEquivalentDemands() {
        MaterialRef material = new MaterialRef(new ResourceLocation("test", "material"), "");

        List<IngredientRef> merged = PureDemandNormalizer.mergeEquivalent(List.of(
                new IngredientRef(List.of(material), 2, NbtMatchMode.ANY),
                new IngredientRef(List.of(material), 3, NbtMatchMode.ANY)));

        assertEquals(List.of(new IngredientRef(
                List.of(material), 5, NbtMatchMode.ANY)), merged);
    }

    @Test
    void doesNotMergeDifferentNbtSemantics() {
        MaterialRef material = new MaterialRef(new ResourceLocation("test", "material"), "");

        List<IngredientRef> merged = PureDemandNormalizer.mergeEquivalent(List.of(
                new IngredientRef(List.of(material), 2, NbtMatchMode.ANY),
                new IngredientRef(List.of(material), 3, NbtMatchMode.EXACT)));

        assertEquals(2, merged.size());
        assertEquals(NbtMatchMode.ANY, merged.get(0).nbtMatchMode());
        assertEquals(NbtMatchMode.EXACT, merged.get(1).nbtMatchMode());
    }

    @Test
    void doesNotMergeCatalystAndConsumedDemands() {
        MaterialRef material = new MaterialRef(new ResourceLocation("test", "tool"), "");

        List<IngredientRef> merged = PureDemandNormalizer.mergeEquivalent(List.of(
                new IngredientRef(List.of(material), 1, NbtMatchMode.ANY,
                        DemandRole.CATALYST),
                new IngredientRef(List.of(material), 2, NbtMatchMode.ANY,
                        DemandRole.CONSUMED)));

        assertEquals(2, merged.size());
        assertEquals(DemandRole.CATALYST, merged.get(0).role());
        assertEquals(DemandRole.CONSUMED, merged.get(1).role());
    }
}
