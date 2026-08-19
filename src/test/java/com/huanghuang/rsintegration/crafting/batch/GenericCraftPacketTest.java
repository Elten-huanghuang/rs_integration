package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericCraftPacketTest extends BootstrapTest {

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
    void infeasiblePurePlanFallsBackToTypedResolverForVirtualIntermediates() {
        PureRecipePlanner.Result incomplete = new PureRecipePlanner.Result(false,
                List.of(), List.of(), Map.of());

        assertFalse(GenericCraftPacket.canUsePrecomputedPlan(incomplete));
        assertFalse(GenericCraftPacket.canUsePrecomputedPlan(null));
    }

    @Test
    void feasiblePurePlanCanBeUsedWithoutMainThreadReplanning() {
        PureRecipePlanner.Result complete = new PureRecipePlanner.Result(true,
                List.of(), List.of(), Map.of());

        assertTrue(GenericCraftPacket.canUsePrecomputedPlan(complete));
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
    void outputDestinationRoundTripsAndInvalidOrdinalsDefaultToRs() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        OutputDestination.PLAYER_INVENTORY.write(buffer);

        assertEquals(OutputDestination.PLAYER_INVENTORY,
                OutputDestination.read(buffer));
        assertEquals(OutputDestination.RS_NETWORK, OutputDestination.byOrdinal(-1));
        assertEquals(OutputDestination.RS_NETWORK, OutputDestination.byOrdinal(99));
    }
}
