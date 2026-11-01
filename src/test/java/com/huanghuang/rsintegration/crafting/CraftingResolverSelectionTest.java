package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingResolverSelectionTest extends BootstrapTest {
    @Test
    void taggedVariantsHaveDistinctStablePreferenceKeys() {
        ItemStack first = new ItemStack(Items.DIAMOND);
        first.getOrCreateTag().putString("gem", "apotheosis:core/warlord");
        ItemStack same = first.copy();
        ItemStack other = new ItemStack(Items.DIAMOND);
        other.getOrCreateTag().putString("gem", "apotheosis:core/samurai");

        ResourceLocation firstKey = CraftingResolver.preferenceKey(first);
        assertEquals(firstKey, CraftingResolver.preferenceKey(same));
        assertNotEquals(firstKey, CraftingResolver.preferenceKey(other));
        assertTrue(CraftingResolver.isStackPreferenceKey(firstKey));
    }

    @Test
    void untaggedStacksKeepLegacyItemKey() {
        assertEquals(new ResourceLocation("minecraft", "diamond"),
                CraftingResolver.preferenceKey(new ItemStack(Items.DIAMOND)));
    }

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

    @Test
    void machineRootsResolveConcreteInputsBeforeTagsAndPreserveOriginalSlotOrder() {
        List<IngredientSpec> specs = List.of(
                new IngredientSpec(
                        Ingredient.of(Items.OAK_LOG, Items.BIRCH_LOG), 1,
                        DemandRole.CONSUMED),
                new IngredientSpec(
                        Ingredient.of(Items.DIAMOND), 1,
                        DemandRole.CONSUMED),
                new IngredientSpec(
                        Ingredient.of(Items.BUCKET), 1,
                        DemandRole.CATALYST));

        assertEquals(List.of(1, 0, 2),
                CraftingResolver.machineRootResolutionOrder(specs));
    }
}
