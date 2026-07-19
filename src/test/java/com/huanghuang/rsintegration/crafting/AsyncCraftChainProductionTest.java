package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncCraftChainProductionTest extends BootstrapTest {

    @Test
    void countsOnlyMatchingRealStacks() {
        var expected = new IBatchDelegate.ExpectedProduction(new ItemStack(Items.IRON_INGOT), 3);
        int actual = AsyncCraftChain.countMatchingProduction(List.of(
                new ItemStack(Items.IRON_INGOT, 2),
                new ItemStack(Items.GOLD_INGOT, 8),
                ItemStack.EMPTY), expected);
        assertEquals(2, actual);
    }

    @Test
    void countsDynamicNbtByItemType() {
        ItemStack actualStack = new ItemStack(Items.IRON_INGOT, 2);
        CompoundTag tag = new CompoundTag();
        tag.putString("runtime", "preserved");
        actualStack.setTag(tag);

        var expected = new IBatchDelegate.ExpectedProduction(new ItemStack(Items.IRON_INGOT), 2);
        assertEquals(2, AsyncCraftChain.countMatchingProduction(List.of(actualStack), expected));
    }

    @Test
    void appendedTerminalKeepsResolverScaledExecutions() {
        var intermediate = new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", "intermediate"), ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"), List.of(), List.of(), false, 2);
        var terminal = new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", "terminal"), ModType.GENERIC,
                new ResourceLocation("test", "ritual"), List.of(), List.of(), false, 1);

        List<CraftingResolver.ResolutionStep> combined =
                AsyncCraftChain.compatibilitySteps(List.of(intermediate), terminal, 2);

        assertEquals(2, combined.get(0).executions());
        assertEquals(2, combined.get(1).executions());
    }

    @Test
    void resolverScaledModIntermediateIsNotMultipliedAgain() {
        var intermediate = new CraftingResolver.ResolutionStep(
                new ResourceLocation("malum", "runewood_plank"), ModType.byId("malum"),
                new ResourceLocation("malum", "recipe"), List.of(), List.of(), false, 3);
        List<CraftingResolver.ResolutionStep> combined =
                AsyncCraftChain.compatibilitySteps(List.of(intermediate),
                        new CraftingResolver.ResolutionStep(
                                new ResourceLocation("test", "terminal"), ModType.GENERIC,
                                new ResourceLocation("minecraft", "crafting"),
                                List.of(), List.of(), false, 1), 4);
        assertEquals(3, combined.get(0).executions());
        assertEquals(4, combined.get(1).executions());
    }

    @Test
    void graphReservationScalesConsumablesButNotReusableWorkerMaterials() {
        List<IngredientSpec> scaled = AsyncCraftChain.scaleGraphSpecsForExecutions(
                List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 2),
                        new IngredientSpec(Ingredient.of(Items.BUCKET), 1)),
                List.of(IBatchDelegate.MaterialReservationScope.PER_OPERATION,
                        IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE),
                3);

        assertEquals(6, scaled.get(0).count());
        assertEquals(1, scaled.get(1).count());
    }

    @Test
    void graphReservationPreservesCatalystRoleAndQuantity() {
        IngredientSpec catalyst = new IngredientSpec(
                Ingredient.of(Items.BUCKET), 1, DemandRole.CATALYST);

        List<IngredientSpec> scaled = AsyncCraftChain.scaleGraphSpecsForExecutions(
                List.of(catalyst),
                List.of(IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE),
                12);

        assertEquals(1, scaled.get(0).count());
        assertEquals(DemandRole.CATALYST, scaled.get(0).role());
    }

    @Test
    void reusableRequirementDoesNotScaleWithExecutions() {
        IngredientSpec consumed = new IngredientSpec(
                Ingredient.of(Items.IRON_INGOT), 2, DemandRole.CONSUMED);
        IngredientSpec catalyst = new IngredientSpec(
                Ingredient.of(Items.BUCKET), 1, DemandRole.CATALYST);

        assertEquals(20, CraftPacketUtils.requiredCount(consumed, 10));
        assertEquals(1, CraftPacketUtils.requiredCount(catalyst, 10));
    }

    @Test
    void nullExpectationOptsOut() {
        assertEquals(0, AsyncCraftChain.countMatchingProduction(
                List.of(new ItemStack(Items.IRON_INGOT, 64)), null));
    }
}
