package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.crafting.graph.UnresolvedDemand;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.crafting.planning.PureDemandTreeInspector;
import com.huanghuang.rsintegration.crafting.planning.AsyncPlanningCoordinator;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class GenericCraftPacketTest extends BootstrapTest {

    @BeforeAll
    static void loadServerConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    @Test
    void preparationAllowsUnresolvedTerminalRootsButRejectsUnresolvedNodeInputs() {
        NodeId nodeId = new NodeId(0);
        OutputPortId outputId = new OutputPortId(nodeId, 0);
        MaterialKey planks = MaterialKey.of(new ItemStack(Items.OAK_PLANKS));
        CraftNode safeNode = new CraftNode(nodeId, new ResourceLocation("test", "planks"),
                ModType.GENERIC.id(), null, 1, List.of(), List.of(), false, null, null,
                List.of(), List.of(new OutputDeclaration(outputId, planks, 4,
                        OutputKind.PRIMARY)));
        RootDemand missingTerminal = new RootDemand(Ingredient.of(Items.DIAMOND), 1, 1,
                new ItemStack(Items.DIAMOND), List.of());
        CraftPlanGraph safe = new CraftPlanGraph(1, List.of(safeNode), List.of(),
                List.of(missingTerminal), List.of(), List.of(nodeId));
        assertTrue(GenericCraftPacket.isPreparationGraphExecutable(safe));

        InputPortId inputId = new InputPortId(nodeId, 0);
        CraftNode blockedNode = new CraftNode(nodeId, new ResourceLocation("test", "planks"),
                ModType.GENERIC.id(), null, 1, List.of(), List.of(), false, null, null,
                List.of(new InputDemand(inputId, Ingredient.of(Items.STICK), 1,
                        DemandRole.CONSUMED, new ItemStack(Items.STICK))),
                List.of(new OutputDeclaration(outputId, planks, 4, OutputKind.PRIMARY)));
        CraftPlanGraph blocked = new CraftPlanGraph(1, List.of(blockedNode), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.OAK_PLANKS), 4, 0,
                        new ItemStack(Items.OAK_PLANKS), List.of(new RootAllocation(
                        new MaterialSource.ProducerOutput(outputId), planks, 4)))),
                List.of(new UnresolvedDemand(inputId, Ingredient.of(Items.STICK), 1,
                        new ItemStack(Items.STICK))), List.of(nodeId));
        assertFalse(GenericCraftPacket.isPreparationGraphExecutable(blocked));
    }

    @Test
    void preparationPrunesBlockedBranchAndKeepsIndependentDependencyChain() {
        NodeId rawNodeId = new NodeId(0);
        NodeId safeNodeId = new NodeId(1);
        NodeId blockedNodeId = new NodeId(2);
        NodeId downstreamNodeId = new NodeId(3);
        OutputPortId rawOutputId = new OutputPortId(rawNodeId, 0);
        OutputPortId safeOutputId = new OutputPortId(safeNodeId, 0);
        OutputPortId blockedOutputId = new OutputPortId(blockedNodeId, 0);
        OutputPortId downstreamOutputId = new OutputPortId(downstreamNodeId, 0);
        MaterialKey planks = MaterialKey.of(new ItemStack(Items.OAK_PLANKS));
        MaterialKey sticks = MaterialKey.of(new ItemStack(Items.STICK));
        MaterialKey gold = MaterialKey.of(new ItemStack(Items.GOLD_INGOT));
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));

        CraftNode rawNode = graphNode(rawNodeId, "raw", List.of(),
                new OutputDeclaration(rawOutputId, planks, 2, OutputKind.PRIMARY));
        InputPortId safeInputId = new InputPortId(safeNodeId, 0);
        CraftNode safeNode = graphNode(safeNodeId, "safe",
                List.of(new InputDemand(safeInputId, Ingredient.of(Items.OAK_PLANKS), 2,
                        DemandRole.CONSUMED, new ItemStack(Items.OAK_PLANKS))),
                new OutputDeclaration(safeOutputId, sticks, 4, OutputKind.PRIMARY));
        InputPortId blockedInputId = new InputPortId(blockedNodeId, 0);
        InputPortId blockedPreparedInputId = new InputPortId(blockedNodeId, 1);
        CraftNode blockedNode = graphNode(blockedNodeId, "blocked",
                List.of(
                        new InputDemand(blockedInputId, Ingredient.of(Items.IRON_INGOT), 1,
                                DemandRole.CONSUMED, new ItemStack(Items.IRON_INGOT)),
                        new InputDemand(blockedPreparedInputId, Ingredient.of(Items.STICK), 4,
                                DemandRole.CONSUMED, new ItemStack(Items.STICK))),
                new OutputDeclaration(blockedOutputId, gold, 1, OutputKind.PRIMARY));
        InputPortId downstreamInputId = new InputPortId(downstreamNodeId, 0);
        CraftNode downstreamNode = graphNode(downstreamNodeId, "downstream",
                List.of(new InputDemand(downstreamInputId, Ingredient.of(Items.GOLD_INGOT), 1,
                        DemandRole.CONSUMED, new ItemStack(Items.GOLD_INGOT))),
                new OutputDeclaration(downstreamOutputId, diamond, 1, OutputKind.PRIMARY));

        CraftPlanGraph graph = new CraftPlanGraph(1,
                List.of(rawNode, safeNode, blockedNode, downstreamNode),
                List.of(
                        new com.huanghuang.rsintegration.crafting.graph.MaterialAllocation(
                                new com.huanghuang.rsintegration.crafting.graph.AllocationId(0),
                                safeInputId, new MaterialSource.ProducerOutput(rawOutputId), planks, 2),
                        new com.huanghuang.rsintegration.crafting.graph.MaterialAllocation(
                                new com.huanghuang.rsintegration.crafting.graph.AllocationId(1),
                                blockedPreparedInputId,
                                new MaterialSource.ProducerOutput(safeOutputId), sticks, 4),
                        new com.huanghuang.rsintegration.crafting.graph.MaterialAllocation(
                                new com.huanghuang.rsintegration.crafting.graph.AllocationId(2),
                                downstreamInputId,
                                new MaterialSource.ProducerOutput(blockedOutputId), gold, 1)),
                List.of(new RootDemand(Ingredient.of(Items.DIAMOND), 1, 0,
                                new ItemStack(Items.DIAMOND), List.of(new RootAllocation(
                                new MaterialSource.ProducerOutput(downstreamOutputId), diamond, 1)))),
                List.of(new UnresolvedDemand(blockedInputId, Ingredient.of(Items.IRON_INGOT),
                        1, new ItemStack(Items.IRON_INGOT))),
                List.of(rawNodeId, safeNodeId, blockedNodeId, downstreamNodeId));
        CraftPlanValidator.validate(graph);

        CraftPlanGraph pruned = GenericCraftPacket.pruneBlockedPreparationGraph(graph);

        assertEquals(List.of(rawNodeId, safeNodeId), pruned.topologicalOrder());
        assertEquals(2, pruned.nodes().size());
        assertEquals(1, pruned.allocations().size());
        assertTrue(pruned.unresolvedDemands().isEmpty());
        assertEquals(1, pruned.rootDemands().size());
        assertEquals(sticks, pruned.rootDemands().get(0).allocations().get(0).material());
        assertEquals(4, pruned.rootDemands().get(0).quantity());
        CraftPlanValidator.validate(pruned);
    }

    @Test
    void preparationReturnsNoGraphWhenEveryNodeIsBlocked() {
        NodeId nodeId = new NodeId(0);
        InputPortId inputId = new InputPortId(nodeId, 0);
        OutputPortId outputId = new OutputPortId(nodeId, 0);
        MaterialKey planks = MaterialKey.of(new ItemStack(Items.OAK_PLANKS));
        CraftNode node = graphNode(nodeId, "blocked_only",
                List.of(new InputDemand(inputId, Ingredient.of(Items.STICK), 1,
                        DemandRole.CONSUMED, new ItemStack(Items.STICK))),
                new OutputDeclaration(outputId, planks, 1, OutputKind.PRIMARY));
        CraftPlanGraph graph = new CraftPlanGraph(1, List.of(node), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.OAK_PLANKS), 1, 0,
                        new ItemStack(Items.OAK_PLANKS), List.of(new RootAllocation(
                        new MaterialSource.ProducerOutput(outputId), planks, 1)))),
                List.of(new UnresolvedDemand(inputId, Ingredient.of(Items.STICK), 1,
                        new ItemStack(Items.STICK))), List.of(nodeId));

        assertNull(GenericCraftPacket.pruneBlockedPreparationGraph(graph));
    }

    private static CraftNode graphNode(NodeId id, String path, List<InputDemand> inputs,
                                       OutputDeclaration output) {
        return new CraftNode(id, new ResourceLocation("test", path), ModType.GENERIC.id(),
                null, 1, List.of(), List.of(), false, null, null, inputs, List.of(output));
    }

    @Test
    void materialLocksRoundTripWithDefensiveCounts() {
        ItemStack selected = new ItemStack(Items.OAK_PLANKS, 64);
        GenericCraftPacket packet = new GenericCraftPacket(
                new ResourceLocation("test", "planks"), true)
                .withMaterialLocks(Map.of("test:planks#abc", selected));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        packet.encode(buffer);
        GenericCraftPacket decoded = GenericCraftPacket.decode(buffer);

        assertEquals(Items.OAK_PLANKS,
                decoded.materialLocks().get("test:planks#abc").getItem());
        assertEquals(1, decoded.materialLocks().get("test:planks#abc").getCount());
        selected.setCount(1);
        assertEquals(1, packet.materialLocks().get("test:planks#abc").getCount());
    }

    @Test
    void materialLockCountIsBounded() {
        Map<String, ItemStack> locks = new HashMap<>();
        for (int i = 0; i <= 128; i++) {
            locks.put("test:r#" + i, new ItemStack(Items.OAK_PLANKS));
        }
        GenericCraftPacket packet = new GenericCraftPacket(
                new ResourceLocation("test", "planks"), true);
        assertThrows(IllegalArgumentException.class, () -> packet.withMaterialLocks(locks));
    }

    @Test
    void taglessRecipeDeclarationIgnoresJeiDisplayNbt() {
        ShapelessRecipe recipe = new ShapelessRecipe(new ResourceLocation("test", "display_nbt"), "",
                net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.BOOK),
                net.minecraft.core.NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.PAPER)));
        ItemStack clicked = new ItemStack(Items.BOOK);
        CompoundTag displayData = new CompoundTag();
        displayData.putString("jei_display", "client-only");
        clicked.setTag(displayData);

        ItemStack selected = GenericCraftPacket.selectTerminalGraphOutput(
                recipe, recipe.getResultItem(net.minecraft.core.RegistryAccess.EMPTY), clicked);

        assertFalse(selected.hasTag());
        assertEquals(Items.BOOK, selected.getItem());
    }

    @Test
    void taggedRecipeDeclarationKeepsConcreteClickedVariant() {
        ItemStack declared = new ItemStack(Items.ENCHANTED_BOOK);
        CompoundTag levelOne = new CompoundTag();
        levelOne.putInt("level", 1);
        declared.setTag(levelOne);
        ShapelessRecipe recipe = new ShapelessRecipe(new ResourceLocation("test", "variant"), "",
                net.minecraft.world.item.crafting.CraftingBookCategory.MISC, declared,
                net.minecraft.core.NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.BOOK)));
        ItemStack clicked = new ItemStack(Items.ENCHANTED_BOOK);
        CompoundTag levelTwo = new CompoundTag();
        levelTwo.putInt("level", 2);
        clicked.setTag(levelTwo);

        ItemStack selected = GenericCraftPacket.selectTerminalGraphOutput(
                recipe, recipe.getResultItem(net.minecraft.core.RegistryAccess.EMPTY), clicked);

        assertEquals(2, selected.getTag().getInt("level"));
    }

    @Test
    void runtimeDependentOutputDropsRecipeAndClickedNbt() {
        ItemStack declared = new ItemStack(Items.BOOK);
        CompoundTag recipeData = new CompoundTag();
        recipeData.putInt("Damage", 0);
        declared.setTag(recipeData);
        ShapelessRecipe recipe = new ShapelessRecipe(new ResourceLocation("test", "runtime_nbt"), "",
                net.minecraft.world.item.crafting.CraftingBookCategory.MISC, declared,
                net.minecraft.core.NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.PAPER)));
        ItemStack clicked = declared.copy();
        CompoundTag clickedData = clicked.getOrCreateTag();
        clickedData.putInt("display_only", 1);

        com.huanghuang.rsintegration.recipe.ModRecipeHandler handler =
                new com.huanghuang.rsintegration.recipe.ModRecipeHandler() {
                    @Override
                    public ModType modType() {
                        return ModType.GENERIC;
                    }

                    @Override
                    public boolean canHandle(net.minecraft.world.item.crafting.Recipe<?> ignored) {
                        return true;
                    }

                    @Override
                    public ItemStack getResultItem(
                            net.minecraft.world.item.crafting.Recipe<?> ignored,
                            net.minecraft.core.RegistryAccess access) {
                        return declared.copy();
                    }

                    @Override
                    public List<IngredientSpec> getIngredients(
                            net.minecraft.world.item.crafting.Recipe<?> ignored) {
                        return List.of();
                    }

                    @Override
                    public boolean hasRuntimeDependentPrimaryNbt(
                            net.minecraft.world.item.crafting.Recipe<?> ignored) {
                        return true;
                    }
                };

        ItemStack selected = GenericCraftPacket.selectTerminalGraphOutput(
                recipe, declared, clicked, handler);

        assertEquals(Items.BOOK, selected.getItem());
        assertFalse(selected.hasTag());
    }

    @Test
    void infeasiblePurePlanningRetriesTypedPlannerForCraftableLowerLevel() {
        var smithingOutput = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("test", "smithing_output"), "");
        var missing = new ImmutableRecipeGraph.IngredientRef(
                List.of(smithingOutput), 1, ImmutableRecipeGraph.NbtMatchMode.ANY,
                com.huanghuang.rsintegration.crafting.graph.DemandRole.CONSUMED);
        PureRecipePlanner.Result incomplete = new PureRecipePlanner.Result(
                PureRecipePlanner.Feasibility.INFEASIBLE, List.of(), List.of(missing),
                Map.of(), PureRecipePlanner.Status.UNRESOLVABLE, 3, 2, 0);

        assertFalse(GenericCraftPacket.shouldRetryTypedPlanning(incomplete, Set.of()));
        assertTrue(GenericCraftPacket.shouldRetryTypedPlanning(
                incomplete, Set.of(smithingOutput.itemId())));

        PureRecipePlanner.Result complete = new PureRecipePlanner.Result(
                PureRecipePlanner.Feasibility.FEASIBLE, List.of(), List.of(),
                Map.of(), PureRecipePlanner.Status.SUCCESS, 3, 0, 0);
        PureRecipePlanner.Result bounded = new PureRecipePlanner.Result(
                PureRecipePlanner.Feasibility.UNKNOWN, List.of(), List.of(missing),
                Map.of(), PureRecipePlanner.Status.SEARCH_LIMIT, 3, 0, 0);
        assertFalse(GenericCraftPacket.shouldRetryTypedPlanning(complete, Set.of()));
        assertFalse(GenericCraftPacket.shouldRetryTypedPlanning(bounded, Set.of()));
    }

    @Test
    void typedMachineFallbackRequiresEveryMissingDemandToBeBlocked() {
        var blockedOutput = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("test", "bound_machine_output"), "");
        var rawMaterial = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("minecraft", "prismarine_shard"), "");
        var blockedDemand = new ImmutableRecipeGraph.IngredientRef(
                List.of(blockedOutput), 1, ImmutableRecipeGraph.NbtMatchMode.ANY,
                com.huanghuang.rsintegration.crafting.graph.DemandRole.CONSUMED);
        var rawDemand = new ImmutableRecipeGraph.IngredientRef(
                List.of(rawMaterial), 7, ImmutableRecipeGraph.NbtMatchMode.ANY,
                com.huanghuang.rsintegration.crafting.graph.DemandRole.CONSUMED);
        PureRecipePlanner.Result mixed = new PureRecipePlanner.Result(
                PureRecipePlanner.Feasibility.INFEASIBLE, List.of(),
                List.of(blockedDemand, rawDemand), Map.of(),
                PureRecipePlanner.Status.UNRESOLVABLE, 8, 2, 0);

        assertTrue(GenericCraftPacket.missingTouchesBlockedOutput(
                mixed.missing(), Set.of(blockedOutput.itemId())));
        assertFalse(GenericCraftPacket.allMissingRequireBlockedOutput(
                mixed.missing(), Set.of(blockedOutput.itemId())));
        assertFalse(GenericCraftPacket.shouldRetryTypedPlanning(
                mixed, Set.of(blockedOutput.itemId())));

        var firstOutput = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("test", "first_machine_output"), "{tier:1}");
        var secondOutput = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("test", "second_machine_output"), "{tier:2}");
        var firstDemand = new ImmutableRecipeGraph.IngredientRef(
                List.of(firstOutput), 1, ImmutableRecipeGraph.NbtMatchMode.EXACT,
                com.huanghuang.rsintegration.crafting.graph.DemandRole.CONSUMED);
        var secondDemand = new ImmutableRecipeGraph.IngredientRef(
                List.of(secondOutput), 1, ImmutableRecipeGraph.NbtMatchMode.EXACT,
                com.huanghuang.rsintegration.crafting.graph.DemandRole.CONSUMED);
        PureRecipePlanner.Result blocked = new PureRecipePlanner.Result(
                PureRecipePlanner.Feasibility.INFEASIBLE, List.of(),
                List.of(firstDemand, secondDemand), Map.of(),
                PureRecipePlanner.Status.UNRESOLVABLE, 8, 2, 0);
        Set<ResourceLocation> blockedIds = Set.of(
                firstOutput.itemId(), secondOutput.itemId());

        assertTrue(GenericCraftPacket.allMissingRequireBlockedOutput(blocked.missing(), blockedIds));
        assertTrue(GenericCraftPacket.shouldRetryTypedPlanning(blocked, blockedIds));
    }

    @Test
    void feasiblePurePlanCanBeUsedWithoutMainThreadReplanning() {
        PureRecipePlanner.Result complete = new PureRecipePlanner.Result(true,
                List.of(), List.of(), Map.of());

        assertTrue(GenericCraftPacket.canUsePrecomputedPlan(complete));
    }

    @Test
    void purePreviewPlanCannotReplaceAPhysicalMachineTerminal() {
        PureRecipePlanner.Result complete = new PureRecipePlanner.Result(true,
                List.of(), List.of(), Map.of());

        assertTrue(GenericCraftPacket.canUsePureExecutionPlan(complete, ModType.GENERIC));
        assertFalse(GenericCraftPacket.canUsePureExecutionPlan(
                complete, ModType.FARMINGFORBLOCKHEADS_MARKET));
    }

    @Test
    void boundedPureSearchStillOpensKnownMissingMaterialTree() {
        var diamondPickaxe = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("minecraft", "diamond_pickaxe"), "");
        PureRecipePlanner.Result timedOut = new PureRecipePlanner.Result(
                PureRecipePlanner.Feasibility.UNKNOWN, List.of(), List.of(), Map.of(),
                PureRecipePlanner.Status.TIME_LIMIT, 10, 0, 0);
        PureDemandTreeInspector.Result missing = new PureDemandTreeInspector.Result(
                PureDemandTreeInspector.Status.MISSING_MATERIALS, 2,
                diamondPickaxe, false);

        assertTrue(GenericCraftPacket.canOpenBoundedMissingPlan(timedOut, missing));
        assertFalse(GenericCraftPacket.canOpenBoundedMissingPlan(timedOut,
                new PureDemandTreeInspector.Result(
                        PureDemandTreeInspector.Status.NODE_LIMIT, 2,
                        diamondPickaxe, false)));
    }

    @Test
    void timedOutPartialTraceOpensPreviewWhenDemandTreeWasComplete() {
        var missingMaterial = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("test", "missing_material"), "");
        var diagnostic = new ImmutableRecipeGraph.IngredientRef(
                List.of(missingMaterial), 3);
        var partialStep = new PureRecipePlanner.PlannedStep(
                new ResourceLocation("test", "partial_step"), 1);
        var completeTree = new PureDemandTreeInspector.Result(
                PureDemandTreeInspector.Status.COMPLETE, 4, null, false);
        PureRecipePlanner.Result timedOut = new PureRecipePlanner.Result(
                PureRecipePlanner.Feasibility.UNKNOWN, List.of(partialStep),
                List.of(diagnostic), Map.of(), PureRecipePlanner.Status.TIME_LIMIT,
                10, 2, 0);

        assertTrue(GenericCraftPacket.canOpenBoundedMissingPlan(timedOut, completeTree));
    }

    @Test
    void timedOutPreviewRequiresBothPartialStepsAndMissingMaterials() {
        var missingMaterial = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("test", "missing_material"), "");
        var diagnostic = new ImmutableRecipeGraph.IngredientRef(
                List.of(missingMaterial), 3);
        var partialStep = new PureRecipePlanner.PlannedStep(
                new ResourceLocation("test", "partial_step"), 1);
        var completeTree = new PureDemandTreeInspector.Result(
                PureDemandTreeInspector.Status.COMPLETE, 4, null, false);
        PureRecipePlanner.Result noSteps = new PureRecipePlanner.Result(
                PureRecipePlanner.Feasibility.UNKNOWN, List.of(), List.of(diagnostic), Map.of(),
                PureRecipePlanner.Status.TIME_LIMIT, 10, 2, 0);
        PureRecipePlanner.Result noMissing = new PureRecipePlanner.Result(
                PureRecipePlanner.Feasibility.UNKNOWN, List.of(partialStep), List.of(), Map.of(),
                PureRecipePlanner.Status.TIME_LIMIT, 10, 2, 0);

        assertFalse(GenericCraftPacket.canOpenBoundedMissingPlan(noSteps, completeTree));
        assertFalse(GenericCraftPacket.canOpenBoundedMissingPlan(noMissing, completeTree));
    }

    @Test
    void typedTimeoutKeepsAConfirmedMissingTreeOpenForInspection() {
        var missingMaterial = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("minecraft", "prismarine_shard"), "");
        PureRecipePlanner.Result incomplete = new PureRecipePlanner.Result(
                PureRecipePlanner.Feasibility.INFEASIBLE, List.of(),
                List.of(new ImmutableRecipeGraph.IngredientRef(
                        List.of(missingMaterial), 7,
                        ImmutableRecipeGraph.NbtMatchMode.ANY)), Map.of(),
                PureRecipePlanner.Status.UNRESOLVABLE, 12, 3, 0);
        var missing = new PureDemandTreeInspector.Result(
                PureDemandTreeInspector.Status.MISSING_MATERIALS, 4,
                missingMaterial, false);

        assertTrue(GenericCraftPacket.canFallbackToBoundedPlanAfterTypedTimeout(
                incomplete, missing, false));
        assertFalse(GenericCraftPacket.canFallbackToBoundedPlanAfterTypedTimeout(
                incomplete, missing, true));
        assertFalse(GenericCraftPacket.canFallbackToBoundedPlanAfterTypedTimeout(
                null, missing, false));
    }

    @Test
    void compatibilityResolverUsesPlanningLifetimeNotPerTickExecutionSlice() {
        assertEquals(2_000, GenericCraftPacket.compatibilityResolverBudgetMs(2_000));
        assertEquals(1, GenericCraftPacket.compatibilityResolverBudgetMs(0));
    }

    @Test
    void boundedPureFailuresReportComplexityInsteadOfTimeLimit() {
        for (PureRecipePlanner.Status status : List.of(
                PureRecipePlanner.Status.TIME_LIMIT,
                PureRecipePlanner.Status.SEARCH_LIMIT,
                PureRecipePlanner.Status.STEP_LIMIT)) {
            PureRecipePlanner.Result result = new PureRecipePlanner.Result(
                    PureRecipePlanner.Feasibility.UNKNOWN, List.of(), List.of(), Map.of(),
                    status, 10, 0, 0);

            assertEquals("rsi.plan.failure.complexity_limit",
                    GenericCraftPacket.purePlanningFailureKey(result, Map.of()));
        }
        var diagnostic = new ImmutableRecipeGraph.IngredientRef(List.of(
                new ImmutableRecipeGraph.MaterialRef(
                        new ResourceLocation("test", "diagnostic_only"), "")), 7);
        PureRecipePlanner.Result timedOut = new PureRecipePlanner.Result(
                PureRecipePlanner.Feasibility.UNKNOWN, List.of(), List.of(diagnostic), Map.of(),
                PureRecipePlanner.Status.TIME_LIMIT, 1, 0, 0);
        PureRecipePlanner.Result searchLimited = new PureRecipePlanner.Result(
                PureRecipePlanner.Feasibility.UNKNOWN, List.of(), List.of(diagnostic), Map.of(),
                PureRecipePlanner.Status.SEARCH_LIMIT, 10, 2, 0);
        assertEquals("rsi.plan.failure.planning_timeout",
                GenericCraftPacket.boundedPreviewFailureKey(timedOut));
        assertEquals("rsi.plan.failure.complexity_limit",
                GenericCraftPacket.boundedPreviewFailureKey(searchLimited));
    }

    @Test
    void independentRawShortageDoesNotEnterTypedCatalystFallback() {
        var catalyst = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("test", "catalyst_output"), "");
        var raw = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("test", "raw_material"), "");
        PureRecipePlanner.Result missingRaw = new PureRecipePlanner.Result(false,
                List.of(), List.of(new ImmutableRecipeGraph.IngredientRef(
                List.of(catalyst), 1), new ImmutableRecipeGraph.IngredientRef(
                List.of(raw), 1)), Map.of());

        assertFalse(GenericCraftPacket.requiresTypedCatalystRoute(
                true, false, missingRaw, java.util.Set.of(catalyst.itemId())));
    }

    @Test
    void typedCatalystFallbackIsRetainedWhenItCanResolveEveryShortage() {
        var catalyst = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("test", "catalyst_output"), "");
        PureRecipePlanner.Result missingCatalyst = new PureRecipePlanner.Result(false,
                List.of(), List.of(new ImmutableRecipeGraph.IngredientRef(
                List.of(catalyst), 1)), Map.of());
        PureRecipePlanner.Result pureSuccess = new PureRecipePlanner.Result(true,
                List.of(), List.of(), Map.of());

        assertTrue(GenericCraftPacket.requiresTypedCatalystRoute(
                true, false, missingCatalyst, java.util.Set.of(catalyst.itemId())));
        assertFalse(GenericCraftPacket.requiresTypedCatalystRoute(
                true, false, pureSuccess, java.util.Set.of(catalyst.itemId())));
        assertFalse(GenericCraftPacket.requiresTypedCatalystRoute(
                false, true, pureSuccess, java.util.Set.of(catalyst.itemId())));
        assertTrue(GenericCraftPacket.requiresTypedCatalystRoute(
                true, true, pureSuccess, java.util.Set.of(catalyst.itemId())));
    }

    @Test
    void detectsSelfAmplifyingCraftingRecipe() {
        ShapelessRecipe duplicate = new ShapelessRecipe(new ResourceLocation("test", "duplicate"), "",
                net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.PAPER, 2),
                net.minecraft.core.NonNullList.of(Ingredient.EMPTY,
                        Ingredient.of(Items.PAPER), Ingredient.of(Items.STONE)));

        assertTrue(GenericCraftPacket.isSelfAmplifyingRecipe(
                duplicate, net.minecraft.core.RegistryAccess.EMPTY));
    }

    @Test
    void ordinaryCraftingRecipeIsNotSelfAmplifying() {
        ShapelessRecipe ordinary = new ShapelessRecipe(new ResourceLocation("test", "ordinary"), "",
                net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.PAPER),
                net.minecraft.core.NonNullList.of(Ingredient.EMPTY,
                        Ingredient.of(Items.SUGAR_CANE)));

        assertFalse(GenericCraftPacket.isSelfAmplifyingRecipe(
                ordinary, net.minecraft.core.RegistryAccess.EMPTY));
    }

    @Test
    void runtimeVirtualRecipesDoNotUsePhysicalMachineSlotPlanning() {
        MarketRecipeWrapper market = new MarketRecipeWrapper(UUID.randomUUID(),
                new ItemStack(Items.DIAMOND), new ItemStack(Items.EMERALD));

        assertFalse(GenericCraftPacket.usesPhysicalMachineInputSlots(market));
    }

    @Test
    void virtualTerminalStillUsesTypedDependencyExecution() {
        ModType virtual = ModType.registerVirtual("test_recursive_virtual_terminal",
                new String[0], GenericBatchDelegate::new);
        ShapelessRecipe recipe = new ShapelessRecipe(
                new ResourceLocation("test", "virtual_terminal"), "",
                net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.DIAMOND),
                net.minecraft.core.NonNullList.of(Ingredient.EMPTY,
                        Ingredient.of(Items.EMERALD)));

        assertTrue(GenericCraftPacket.requiresTypedTerminalExecution(recipe, virtual));
        assertFalse(GenericCraftPacket.requiresTypedTerminalExecution(recipe, ModType.GENERIC));
    }

    @Test
    void marketRepeatAvailabilityUsesArithmeticInsteadOfExpandedIngredients() {
        MarketRecipeWrapper market = new MarketRecipeWrapper(UUID.randomUUID(),
                new ItemStack(Items.TOTEM_OF_UNDYING), new ItemStack(Items.EMERALD, 32));
        Map<CraftingResolver.StackKey, Integer> available = Map.of(
                new CraftingResolver.StackKey(Items.EMERALD, null), 2048);

        var exact = GenericCraftPacket.marketTradeAvailability(market, available, 64);
        var shortage = GenericCraftPacket.marketTradeAvailability(market, available, 65);

        assertEquals(2048, exact.required());
        assertEquals(2048, exact.available());
        assertTrue(exact.feasible());
        assertEquals(2080, shortage.required());
        assertFalse(shortage.feasible());
    }

    @Test
    void previewAndExecutionCacheKeysShareTheTerminalTypeAndVariant() {
        UUID player = UUID.randomUUID();
        ResourceLocation recipe = new ResourceLocation("crafttweaker", "typed_recipe");
        Map<String, String> forced = Map.of("minecraft:iron", "minecraft:iron_ingot");
        ItemStack variant = new ItemStack(Items.ENCHANTED_BOOK);
        CompoundTag tag = new CompoundTag();
        tag.putInt("level", 2);
        variant.setTag(tag);

        assertFalse(GenericCraftPacket.planCacheKey(player, recipe, forced, 4, variant,
                        ModType.GENERIC).equals(
                GenericCraftPacket.planCacheKey(player, recipe, forced, 4, null,
                        ModType.GENERIC)));
        assertFalse(GenericCraftPacket.planCacheKey(player, recipe, forced, 4, variant,
                        ModType.GENERIC).equals(
                GenericCraftPacket.planCacheKey(player, recipe, forced, 4, variant,
                        ModType.FARMINGFORBLOCKHEADS_MARKET)));
    }

    @Test
    void asyncResultMustRemainBoundToItsOriginalRequest() {
        UUID playerId = UUID.randomUUID();
        ResourceLocation recipeId = new ResourceLocation("test", "recipe");
        ResourceLocation forcedItem = new ResourceLocation("minecraft", "diamond");
        ResourceLocation forcedRecipe = new ResourceLocation("test", "diamond_recipe");
        Map<ResourceLocation, ResourceLocation> overrides = Map.of(forcedItem, forcedRecipe);
        PlanningSnapshot snapshot = new PlanningSnapshot(playerId, 7, 3, recipeId,
                Map.of(), overrides, new ImmutableRecipeGraph(Map.of()),
                "network", "binding", false);

        assertTrue(GenericCraftPacket.matchesAsyncRequest(
                snapshot, playerId, recipeId, 7, overrides));
        assertFalse(GenericCraftPacket.matchesAsyncRequest(
                snapshot, playerId, recipeId, 8, overrides));
        assertFalse(GenericCraftPacket.matchesAsyncRequest(
                snapshot, playerId, recipeId, 7, Map.of()));
    }

    @Test
    void cancelledAndStaleAsyncPlansNeverTriggerSynchronousFallback() {
        assertFalse(GenericCraftPacket.shouldFallbackAfterAsyncFailure(
                new CancellationException("superseded")));
        PlanningSnapshot snapshot = new PlanningSnapshot(UUID.randomUUID(), 1, 1,
                new ResourceLocation("test", "recipe"), Map.of(), Map.of(),
                new ImmutableRecipeGraph(Map.of()), "network", "binding", false);
        assertFalse(GenericCraftPacket.shouldFallbackAfterAsyncFailure(
                new AsyncPlanningCoordinator.StalePlanningResultException(snapshot)));
        assertFalse(GenericCraftPacket.shouldFallbackAfterAsyncFailure(
                new RejectedExecutionException("planner queue full")));
        assertTrue(GenericCraftPacket.shouldFallbackAfterAsyncFailure(
                new IllegalStateException("planner failed")));
    }

    @Test
    void synchronousTerminalStepUsesRequestedExecutionCount() {
        var step = GenericCraftPacket.genericTerminalStep(
                new ResourceLocation("minecraft", "iron_ingot"), 6);

        assertEquals(6, step.executions());
    }

    @Test
    void genericExecutionStepsAcceptImmutableProjectedSteps() {
        var intermediate = genericStep("taint_earth_heart", 1);
        List<CraftingResolver.ResolutionStep> projectedSteps = List.of(intermediate);
        ResourceLocation target = new ResourceLocation("crafttweaker", "avarice_scroll");

        List<CraftingResolver.ResolutionStep> executionSteps =
                GenericCraftPacket.genericExecutionSteps(projectedSteps, target, 4);

        assertEquals(List.of(intermediate), projectedSteps);
        assertEquals(2, executionSteps.size());
        assertEquals(target, executionSteps.get(1).recipeId());
        assertEquals(4, executionSteps.get(1).executions());
    }

    @Test
    void pureCraftingChainUsesConfiguredOperationThreshold() {
        List<CraftingResolver.ResolutionStep> steps = List.of(
                new CraftingResolver.ResolutionStep(
                        new ResourceLocation("test", "intermediate"), ModType.GENERIC,
                        new ResourceLocation("minecraft", "crafting"),
                        List.of(), List.of(), false, 3),
                genericStep("terminal", 5));

        assertEquals(8, GenericCraftPacket.totalExecutions(steps));
        assertFalse(GenericCraftPacket.shouldExecuteGenericChainAsync(steps, 8));
        assertTrue(GenericCraftPacket.shouldExecuteGenericChainAsync(steps, 7));
    }

    @Test
    void typedMachineStepCanNeverUseTheSynchronousCraftingExecutor() {
        List<CraftingResolver.ResolutionStep> generic = List.of(genericStep("terminal", 1));
        List<CraftingResolver.ResolutionStep> physical = List.of(
                new CraftingResolver.ResolutionStep(
                        new ResourceLocation("goety", "moonstone_plague"), ModType.CUSTOM_GUI,
                        new ResourceLocation("goety", "ritual"),
                        List.of(), List.of(), false, 1));

        assertTrue(GenericCraftPacket.canExecuteSynchronously(generic));
        assertFalse(GenericCraftPacket.canExecuteSynchronously(physical));
    }

    @Test
    void cachedPurePlanRoutesOnlyTypedOrLargeNetworkChainsAsynchronously() {
        List<CraftingResolver.ResolutionStep> smallGeneric = List.of(
                genericStep("intermediate", 1), genericStep("terminal", 1));
        List<CraftingResolver.ResolutionStep> largeGeneric = List.of(
                genericStep("intermediate", 5), genericStep("terminal", 4));
        List<CraftingResolver.ResolutionStep> physical = List.of(
                genericStep("intermediate", 1),
                new CraftingResolver.ResolutionStep(
                        new ResourceLocation("minecraft", "iron_ingot"), ModType.CUSTOM_GUI,
                        new ResourceLocation("minecraft", "blasting"),
                        List.of(), List.of(), false, 1),
                genericStep("terminal", 1));

        assertEquals(GenericCraftPacket.CachedPurePlanExecution.SYNCHRONOUS,
                GenericCraftPacket.selectCachedPurePlanExecution(smallGeneric, true, 8));
        assertEquals(GenericCraftPacket.CachedPurePlanExecution.ASYNCHRONOUS,
                GenericCraftPacket.selectCachedPurePlanExecution(largeGeneric, true, 8));
        assertEquals(GenericCraftPacket.CachedPurePlanExecution.ASYNCHRONOUS,
                GenericCraftPacket.selectCachedPurePlanExecution(physical, true, 8));
        assertEquals(GenericCraftPacket.CachedPurePlanExecution.REPLAN,
                GenericCraftPacket.selectCachedPurePlanExecution(physical, false, 8));
    }

    @Test
    void pureCraftingOperationCountSaturates() {
        List<CraftingResolver.ResolutionStep> steps = List.of(
                genericStep("first", Integer.MAX_VALUE), genericStep("second", 1));

        assertEquals(Integer.MAX_VALUE, GenericCraftPacket.totalExecutions(steps));
    }

    private static CraftingResolver.ResolutionStep genericStep(String path, int executions) {
        return new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", path), ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"),
                List.of(), List.of(), false, executions);
    }

    @Test
    void smithingWaitsForAsynchronousIntermediateBeforeTerminalStep() {
        ResourceLocation intermediateId = new ResourceLocation("test", "dark_helmet");
        ResourceLocation smithingId = new ResourceLocation("test", "divine_gold_helmet");
        var intermediate = new CraftingResolver.ResolutionStep(
                intermediateId, ModType.CUSTOM_GUI, new ResourceLocation("test", "machine"));

        List<CraftingResolver.ResolutionStep> chain = GenericCraftPacket.smithingAsyncSteps(
                List.of(intermediate), smithingId, 3);

        assertEquals(2, chain.size());
        assertEquals(intermediateId, chain.get(0).recipeId());
        assertEquals(smithingId, chain.get(1).recipeId());
        assertEquals(ModType.GENERIC, chain.get(1).modType());
        assertEquals(3, chain.get(1).executions());
    }

    @Test
    void genericOnlySmithingIntermediatesStaySynchronous() {
        var intermediate = new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", "dark_helmet"), ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"));

        assertTrue(GenericCraftPacket.smithingAsyncSteps(
                List.of(intermediate), new ResourceLocation("test", "divine_gold_helmet"), 1)
                .isEmpty());
    }

    @Test
    void missingDirectInputsKeepPureCraftingPreviewOffTheServerThread() {
        assertTrue(GenericCraftPacket.shouldSubmitRoutedPreview(false, false, false, false));
        assertFalse(GenericCraftPacket.shouldSubmitRoutedPreview(true, false, false, false));
        assertFalse(GenericCraftPacket.shouldSubmitRoutedPreview(false, true, false, false));
        assertFalse(GenericCraftPacket.shouldSubmitRoutedPreview(false, false, true, false));
        assertFalse(GenericCraftPacket.shouldSubmitRoutedPreview(false, false, false, true));
        assertTrue(GenericCraftPacket.shouldUseAsyncPurePreview(true, false));
        assertFalse(GenericCraftPacket.shouldUseAsyncPurePreview(true, true));
        assertFalse(GenericCraftPacket.shouldUseAsyncPurePreview(false, false));
    }

    @Test
    void backgroundSmithingPlanRetainsGenericSmithingTerminalSemantics() {
        ResourceLocation recipeId = new ResourceLocation("test", "background_smithing");

        CraftingResolver.ResolutionStep step =
                GenericCraftPacket.backgroundPhysicalTerminalStep(
                        recipeId, ModType.byId("smithing"), true, 4);

        assertEquals(ModType.GENERIC, step.modType());
        assertEquals(new ResourceLocation("minecraft", "smithing"), step.recipeTypeId());
        assertFalse(step.inferMode());
        assertEquals(4, step.executions());
    }

    @Test
    void physicalMachineRecipesRequireABindingContext() {
        assertTrue(GenericCraftPacket.requiresBoundMachine(ModType.CUSTOM_GUI));
        assertFalse(GenericCraftPacket.requiresBoundMachine(ModType.GENERIC));
        assertFalse(GenericCraftPacket.requiresBoundMachine(null));

        SmithingTransformRecipe smithing = new SmithingTransformRecipe(
                new ResourceLocation("test", "smithing_gate"),
                Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                Ingredient.of(Items.DIAMOND_CHESTPLATE),
                Ingredient.of(Items.NETHERITE_INGOT),
                new ItemStack(Items.NETHERITE_CHESTPLATE));
        assertTrue(GenericCraftPacket.requiresBoundMachine(smithing, ModType.GENERIC));

        ShapelessRecipe customCrafting = new ShapelessRecipe(
                new ResourceLocation("slashblade", "custom_crafting_gate"), "",
                net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.DIAMOND),
                net.minecraft.core.NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STICK)));
        assertFalse(GenericCraftPacket.requiresBoundMachine(customCrafting, ModType.CUSTOM_GUI));
    }

    @Test
    void pureCraftingStepRetainsSameOutputMachineRecipeAsAlternative() {
        ShapelessRecipe crafting = new ShapelessRecipe(
                new ResourceLocation("goety", "magic_emerald_craft"), "",
                net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.EMERALD),
                net.minecraft.core.NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.LAPIS_LAZULI)));
        ShapelessRecipe infuser = new ShapelessRecipe(
                new ResourceLocation("goety", "magic_emerald"), "",
                net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.EMERALD),
                net.minecraft.core.NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.REDSTONE)));
        ResourceLocation craftingType = new ResourceLocation("minecraft", "crafting");
        var step = new CraftingResolver.ResolutionStep(crafting.getId(), ModType.GENERIC,
                craftingType, List.of(), List.of(), false, 1, null, null);
        Map<net.minecraft.world.item.Item, List<RecipeIndex.Entry>> index = Map.of(
                Items.EMERALD, List.of(
                        new RecipeIndex.Entry(crafting, ModType.GENERIC, craftingType),
                        new RecipeIndex.Entry(infuser, ModType.CUSTOM_GUI, craftingType)));

        var enriched = GenericCraftPacket.attachIndexedAlternatives(
                List.of(step), index, net.minecraft.core.RegistryAccess.EMPTY);

        assertEquals(List.of(infuser.getId()), enriched.get(0).alternativeIds());
        assertEquals(List.of(ModType.CUSTOM_GUI.id()), enriched.get(0).alternativeModTypes());
    }

    @Test
    void terminalInputGraphCoversWholeBatchWithoutScalingCatalysts() {
        List<IngredientSpec> scaled = GenericCraftPacket.scaleIngredientSpecs(List.of(
                new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1, DemandRole.CONSUMED),
                new IngredientSpec(Ingredient.of(Items.BUCKET), 1, DemandRole.CATALYST)), 6);

        assertEquals(6, scaled.get(0).count());
        assertEquals(DemandRole.CONSUMED, scaled.get(0).role());
        assertEquals(1, scaled.get(1).count());
        assertEquals(DemandRole.CATALYST, scaled.get(1).role());
    }

    @Test
    void selfAmplifyingTerminalScalesCostsButKeepsOneSeed() {
        ItemStack output = new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2);
        List<IngredientSpec> scaled = GenericCraftPacket.scaleTerminalIngredientSpecs(List.of(
                new IngredientSpec(Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), 1),
                new IngredientSpec(Ingredient.of(Items.DIAMOND), 7),
                new IngredientSpec(Ingredient.of(Items.NETHERRACK), 1)), output, 6);

        assertEquals(1, scaled.get(0).count());
        assertEquals(42, scaled.get(1).count());
        assertEquals(6, scaled.get(2).count());
    }

    @Test
    void shapedDisplayKeepsCatalystRoleAlignedAcrossEmptySlots() {
        List<Ingredient> displayed = List.of(
                Ingredient.EMPTY, Ingredient.of(Items.DIAMOND), Ingredient.of(Items.IRON_INGOT));
        List<IngredientSpec> specs = List.of(
                IngredientSpec.EMPTY,
                new IngredientSpec(Ingredient.of(Items.DIAMOND), 1, DemandRole.CATALYST),
                new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1, DemandRole.CONSUMED));

        assertEquals(List.of(DemandRole.CONSUMED, DemandRole.CATALYST, DemandRole.CONSUMED),
                GenericCraftPacket.alignInputRoles(displayed, specs));
    }

    @Test
    void bulkIngredientConsumptionScalesWithVariantsNotRepeatCount() {
        Map<net.minecraft.world.item.Item, Integer> available = new HashMap<>();
        available.put(Items.DIAMOND, 1024);

        Map<net.minecraft.world.item.Item, Integer> consumed =
                GenericCraftPacket.consumeIngredientCount(
                        Ingredient.of(Items.DIAMOND), 1024, available);

        assertEquals(Map.of(Items.DIAMOND, 1024), consumed);
        assertEquals(0, available.get(Items.DIAMOND));
    }

    @Test
    void anyNbtShortageRemainsARealMaterialShortage() {
        var demand = new ImmutableRecipeGraph.IngredientRef(List.of(
                new ImmutableRecipeGraph.MaterialRef(
                        new ResourceLocation("minecraft", "diamond_sword"), "")),
                1, ImmutableRecipeGraph.NbtMatchMode.ANY);
        Map<CraftingResolver.StackKey, Integer> available = Map.of(
                new CraftingResolver.StackKey(Items.DIAMOND_SWORD, "{Damage:7}"), 1);

        assertFalse(GenericCraftPacket.hasPureNbtMismatch(List.of(demand), available));
    }

    @Test
    void exactNbtShortageIsReportedOnlyWhenSameItemStockIsSufficient() {
        var demand = new ImmutableRecipeGraph.IngredientRef(List.of(
                new ImmutableRecipeGraph.MaterialRef(
                        new ResourceLocation("minecraft", "diamond_sword"), "{Damage:0}")),
                2, ImmutableRecipeGraph.NbtMatchMode.EXACT);

        assertTrue(GenericCraftPacket.hasPureNbtMismatch(List.of(demand), Map.of(
                new CraftingResolver.StackKey(Items.DIAMOND_SWORD, "{Damage:7}"), 2)));
        assertFalse(GenericCraftPacket.hasPureNbtMismatch(List.of(demand), Map.of(
                new CraftingResolver.StackKey(Items.DIAMOND_SWORD, "{Damage:7}"), 1)));
    }

    @Test
    void unboundMachineRecipesAreRemovedWithoutHidingUsableAlternatives() {
        var output = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("minecraft", "diamond"), "");
        var input = new ImmutableRecipeGraph.IngredientRef(List.of(
                new ImmutableRecipeGraph.MaterialRef(
                        new ResourceLocation("minecraft", "coal"), "")), 1);
        var unbound = new ImmutableRecipeGraph.RecipeNode(
                new ResourceLocation("test", "unbound"), output, 1, List.of(input),
                "test_machine", new ResourceLocation("test", "machine"));
        var crafting = new ImmutableRecipeGraph.RecipeNode(
                new ResourceLocation("test", "crafting"), output, 1, List.of(input));

        var withAlternative = GenericCraftPacket.filterRecipeGraph(
                new ImmutableRecipeGraph(Map.of(output, List.of(unbound, crafting))),
                node -> !"test_machine".equals(node.modTypeId()));
        assertEquals(List.of(crafting), withAlternative.graph().recipesByOutput().get(output));
        assertTrue(withAlternative.blockedOutputIds().isEmpty());

        var onlyUnbound = GenericCraftPacket.filterRecipeGraph(
                new ImmutableRecipeGraph(Map.of(output, List.of(unbound))), node -> false);
        assertFalse(onlyUnbound.graph().recipesByOutput().containsKey(output));
        assertEquals(java.util.Set.of(output.itemId()), onlyUnbound.blockedOutputIds());
        assertTrue(GenericCraftPacket.missingTouchesBlockedOutput(
                List.of(new ImmutableRecipeGraph.IngredientRef(List.of(output), 1)),
                onlyUnbound.blockedOutputIds()));
    }

    @Test
    void runtimeUnavailableRecipesAreRemovedWithoutBeingReportedAsUnboundMachines() {
        var output = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("minecraft", "diamond"), "");
        var input = new ImmutableRecipeGraph.IngredientRef(List.of(
                new ImmutableRecipeGraph.MaterialRef(
                        new ResourceLocation("minecraft", "coal"), "")), 1);
        var unavailableVirtual = new ImmutableRecipeGraph.RecipeNode(
                new ResourceLocation("test", "virtual"), output, 1, List.of(input),
                "test_virtual", new ResourceLocation("test", "virtual"));

        var filtered = GenericCraftPacket.filterRecipeGraph(
                new ImmutableRecipeGraph(Map.of(output, List.of(unavailableVirtual))),
                node -> false, node -> false);

        assertFalse(filtered.graph().recipesByOutput().containsKey(output));
        assertTrue(filtered.blockedOutputIds().isEmpty());
    }

    @Test
    void combinedRecipeFilterEvaluatesMachineStateOnlyAfterRuntimeState() {
        var output = new ImmutableRecipeGraph.MaterialRef(
                new ResourceLocation("minecraft", "diamond"), "");
        var input = new ImmutableRecipeGraph.IngredientRef(List.of(
                new ImmutableRecipeGraph.MaterialRef(
                        new ResourceLocation("minecraft", "coal"), "")), 1);
        var runtimeRejected = new ImmutableRecipeGraph.RecipeNode(
                new ResourceLocation("test", "runtime_rejected"), output, 1, List.of(input));
        var usable = new ImmutableRecipeGraph.RecipeNode(
                new ResourceLocation("test", "usable"), output, 1, List.of(input));
        AtomicInteger runtimeChecks = new AtomicInteger();
        AtomicInteger machineChecks = new AtomicInteger();

        var filtered = GenericCraftPacket.filterRecipeGraph(
                new ImmutableRecipeGraph(Map.of(output, List.of(runtimeRejected, usable))),
                node -> {
                    runtimeChecks.incrementAndGet();
                    return node != runtimeRejected;
                }, node -> {
                    machineChecks.incrementAndGet();
                    return true;
                });

        assertEquals(2, runtimeChecks.get());
        assertEquals(1, machineChecks.get());
        assertEquals(List.of(usable), filtered.graph().recipesByOutput().get(output));
        assertTrue(filtered.blockedOutputIds().isEmpty());
    }

    @Test
    void boundedPlannerLogSummaryDoesNotSerializeNbtAlternatives() {
        var item = new ResourceLocation("minecraft", "iron_chestplate");
        List<ImmutableRecipeGraph.MaterialRef> alternatives = new java.util.ArrayList<>();
        for (int index = 0; index < 100; index++) {
            alternatives.add(new ImmutableRecipeGraph.MaterialRef(item,
                    "{display:{Name:\"variant-" + index + "\"}}"));
        }
        var demand = new ImmutableRecipeGraph.IngredientRef(alternatives, 7,
                ImmutableRecipeGraph.NbtMatchMode.ANY);

        String summary = GenericCraftPacket.summarizePureDemands(List.of(demand));

        assertTrue(summary.contains("minecraft:iron_chestplate"));
        assertTrue(summary.contains("count=7"));
        assertTrue(summary.contains("alternatives=100"));
        assertFalse(summary.contains("variant-"));
        assertTrue(summary.length() < 256);
    }

    @Test
    void outputDestinationRoundTripsAndInvalidOrdinalsDefaultToRs() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        OutputDestination.PLAYER_INVENTORY.write(buffer);

        assertEquals(OutputDestination.PLAYER_INVENTORY,
                OutputDestination.read(buffer));
        assertEquals(OutputDestination.RS_NETWORK, OutputDestination.byOrdinal(-1));
        assertEquals(OutputDestination.RS_NETWORK, OutputDestination.byOrdinal(99));
    }

    @Test
    void packetRoundTripPreservesPlayerInventoryDestination() {
        GenericCraftPacket original = new GenericCraftPacket(
                new ResourceLocation("test", "player_output"), false, Map.of(),
                null, null, 1, false, null, new ItemStack(Items.DIAMOND),
                42L, OutputDestination.PLAYER_INVENTORY);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        original.encode(buffer);
        GenericCraftPacket decoded = GenericCraftPacket.decode(buffer);

        assertEquals(OutputDestination.PLAYER_INVENTORY, decoded.outputDestination());
    }
}
