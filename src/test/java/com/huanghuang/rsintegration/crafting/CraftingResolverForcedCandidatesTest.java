package com.huanghuang.rsintegration.crafting;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftingResolverForcedCandidatesTest {

    @Test
    void forcedExecutionDoesNotDestroyTheSelectableCandidateList() {
        ResourceLocation ars = new ResourceLocation("ars_nouveau", "efficiency_1");
        ResourceLocation goety = new ResourceLocation("goety", "enchant/efficiency");
        List<ResourceLocation> selectable = List.of(ars, goety);

        List<ResourceLocation> execution = CraftingResolver.forcedExecutionCandidates(
                selectable, id -> id, goety);
        List<ResourceLocation> validationOrder = CraftingResolver.prioritizeForcedCandidate(
                selectable, id -> id, goety);

        assertEquals(List.of(goety), execution);
        assertEquals(List.of(goety, ars), validationOrder);
        assertEquals(List.of(ars, goety), selectable);
    }
}
