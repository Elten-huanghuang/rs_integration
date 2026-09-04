package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void vanillaDependenciesCanExecuteInline() {
        var vanilla = new CraftingResolver.ResolutionStep(
                new ResourceLocation("minecraft", "stick"), ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"));

        assertFalse(CraftPacketUtils.requiresOuterDag(List.of(vanilla)));
    }

    @Test
    void multiBlockDependencyMustBePlannedInOuterDag() {
        var vanilla = new CraftingResolver.ResolutionStep(
                new ResourceLocation("minecraft", "stick"), ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"));
        var multiBlock = new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", "multi_block"), ModType.CUSTOM_GUI,
                new ResourceLocation("test", "machine"));

        assertTrue(CraftPacketUtils.requiresOuterDag(List.of(vanilla, multiBlock)));
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
    void findsEveryReusableMaterialInsteadOfOnlyTheFirst() {
        assertEquals(List.of(0, 2), AsyncCraftChain.reusableMaterialIndices(List.of(
                IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE,
                IBatchDelegate.MaterialReservationScope.PER_OPERATION,
                IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE), 3));
    }

    @Test
    void graphCheckoutRejectsUnconsumedPlannedFragments() {
        assertTrue(AsyncCraftChain.graphMaterialPoolsDrained(
                List.of(ItemStack.EMPTY), List.of()));
        assertFalse(AsyncCraftChain.graphMaterialPoolsDrained(
                List.of(new ItemStack(Items.IRON_INGOT)), List.of()));
        assertFalse(AsyncCraftChain.graphMaterialPoolsDrained(
                List.of(), List.of(new ItemStack(Items.GOLD_INGOT))));
    }

    @Test
    void oversizedMachineGraphNodeUsesTickSlicedFlatExecution() {
        var machine = new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", "machine"), ModType.CUSTOM_GUI,
                new ResourceLocation("test", "machine"), List.of(), List.of(), false, 33);

        assertTrue(AsyncCraftChain.requiresFlatExecutionForOversizedNode(
                List.of(machine), 8, 32));
        assertFalse(AsyncCraftChain.requiresFlatExecutionForOversizedNode(
                List.of(machine), 8, 33));
    }

    @Test
    void oversizedVanillaGraphNodeUsesItsStricterTickLimit() {
        var vanilla = new CraftingResolver.ResolutionStep(
                new ResourceLocation("minecraft", "stick"), ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"), List.of(), List.of(), false, 9);

        assertTrue(AsyncCraftChain.requiresFlatExecutionForOversizedNode(
                List.of(vanilla), 8, 32));
    }

    @Test
    void graphNodeTargetKeepsIntermediateEnchantLevel() {
        ItemStack efficiencyFour = EnchantedBookItem.createForEnchantment(
                new EnchantmentInstance(Enchantments.BLOCK_EFFICIENCY, 4));
        NodeId nodeId = new NodeId(4);
        CraftNode node = new CraftNode(nodeId,
                new ResourceLocation("goety", "enchant/efficiency"), "goety", null,
                2, List.of(), List.of(), false, null, null, List.of(),
                List.of(new OutputDeclaration(new OutputPortId(nodeId, 0),
                        MaterialKey.of(efficiencyFour), 2, OutputKind.PRIMARY)));

        ItemStack target = AsyncCraftChain.graphNodeTargetOutput(node);

        assertEquals(1, target.getCount());
        assertEquals(4, EnchantmentHelper.getEnchantments(target)
                .getOrDefault(Enchantments.BLOCK_EFFICIENCY, 0));
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

    @Test
    void graphFinalOutputUsesRootDeclarationInsteadOfClickedStackNbt() {
        MaterialKey declared = new MaterialKey(Items.DIAMOND_SWORD, null);
        MaterialSource source = new MaterialSource.ProducerOutput(
                new OutputPortId(new NodeId(0), 0));
        CraftPlanGraph graph = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                List.of(), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.DIAMOND_SWORD), 1, 0,
                        new ItemStack(Items.DIAMOND_SWORD),
                        List.of(new RootAllocation(source, declared, 1)))),
                List.of(), List.of());
        ItemStack runtimeOutput = new ItemStack(Items.DIAMOND_SWORD);
        runtimeOutput.getOrCreateTag().putInt("runtime_state", 1);

        assertTrue(AsyncCraftChain.matchesGraphFinalOutput(graph, runtimeOutput));
        assertFalse(AsyncCraftChain.matchesGraphFinalOutput(graph,
                new ItemStack(Items.IRON_SWORD)));
    }

    @Test
    void completeGraphRootStillRoutesRuntimeNbtOutputForFlatFallback() {
        MaterialKey declared = new MaterialKey(Items.DIAMOND_SWORD, null);
        MaterialSource source = new MaterialSource.ProducerOutput(
                new OutputPortId(new NodeId(0), 0));
        CraftPlanGraph graph = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                List.of(), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.DIAMOND_SWORD), 1, 0,
                        new ItemStack(Items.DIAMOND_SWORD),
                        List.of(new RootAllocation(source, declared, 1)))),
                List.of(), List.of());
        ItemStack runtimeOutput = new ItemStack(Items.DIAMOND_SWORD);
        runtimeOutput.getOrCreateTag().putString("malum_runtime_state", "changed");

        ItemStack clickedOutput = new ItemStack(Items.DIAMOND_SWORD);
        clickedOutput.getOrCreateTag().putString("preview_state", "different");

        assertTrue(AsyncCraftChain.matchesFinalTarget(
                runtimeOutput, graph, true, clickedOutput));
        assertFalse(AsyncCraftChain.matchesFinalTarget(
                runtimeOutput, graph, false, clickedOutput));
    }
}
