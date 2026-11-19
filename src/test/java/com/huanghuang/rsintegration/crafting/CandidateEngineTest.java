package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.mods.vanilla.VanillaMachineRecipeHandler;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static com.huanghuang.rsintegration.testutil.SlashBladeFixtures.blade;
import static com.huanghuang.rsintegration.testutil.SlashBladeFixtures.ingredient;

class CandidateEngineTest extends BootstrapTest {

    @BeforeAll
    static void initializeMod() {
        DeepPlanningFixture.initializeMod();
    }

    @Test
    void reusedScoreGroupsStillReadStockAndContextualInputs() {
        var ore = Ingredient.of(Items.RAW_IRON);
        var recipe = new BlastingRecipe(ResourceLocation.parse("test:score_context"), "", CookingBookCategory.MISC,
                ore, new ItemStack(Items.IRON_INGOT), 0, 100);
        var entry = new RecipeIndex.Entry(recipe, ModType.GENERIC, ResourceLocation.parse("minecraft:blasting"));
        var level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        var handler = mock(ModRecipeHandler.class);
        var current = new ArrayList<>(List.of(new IngredientSpec(ore, 1)));
        int[] calls = {0};
        when(handler.getPlanningIngredients(eq(recipe), isNull(), any())).thenAnswer(call -> {
            calls[0]++;
            return current;
        });
        try (var handlers = mockStatic(ModRecipeHandlers.class);
             var index = mockStatic(RecipeIndex.class);
             var utils = mockStatic(CraftPacketUtils.class, CALLS_REAL_METHODS)) {
            handlers.when(() -> ModRecipeHandlers.claimFor(recipe)).thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));
            handlers.when(() -> ModRecipeHandlers.tryGetResultItem(recipe, RegistryAccess.EMPTY))
                    .thenReturn(new ItemStack(Items.IRON_INGOT));
            utils.when(() -> CraftPacketUtils.extractSingleOperationRecursiveIngredientSpecs(recipe))
                    .thenReturn(List.copyOf(current));
            var ctx = new ResolutionContext(level, Map.of(Items.IRON_INGOT, List.of(entry)),
                    List.of(new ItemStack(Items.RAW_IRON)), null);
            ctx.deadlineNanos = Long.MAX_VALUE;
            ctx.profile = new CompatibilityProfile();
            int stocked = diagnosticScore(ctx);
            ctx.beginUndo();
            assertTrue(ctx.consumeMatchingDetailed(ore, 1).complete());
            assertEquals(stocked - 10, diagnosticScore(ctx));
            assertEquals(1, ctx.profile.groupHits);
            ctx.rollback();
            assertEquals(stocked, diagnosticScore(ctx));
            current.set(0, new IngredientSpec(Ingredient.of(Items.DIAMOND), 3));
            assertEquals(stocked - 12, diagnosticScore(ctx));
            assertEquals(2, ctx.profile.groupHits);
            assertEquals(4, calls[0]);
        }
    }

    private static int diagnosticScore(ResolutionContext ctx) {
        var diagnostics = new ArrayList<CandidateEngine.CandidateDiagnostic>();
        CandidateEngine.findCandidates(Ingredient.of(Items.IRON_INGOT), ctx, diagnostics);
        assertEquals(1, diagnostics.size());
        assertFalse(diagnostics.get(0).skipped());
        return diagnostics.get(0).score();
    }

    @Test
    void changingDisplayedNbtInvalidatesGroupedSlots() {
        var ore = Ingredient.of(Items.RAW_IRON);
        var other = Ingredient.of(Items.RAW_IRON);
        var specs = List.of(new IngredientSpec(ore, 1), new IngredientSpec(other, 2));
        var groups = CandidateEngine.specDemands(specs).values();
        assertEquals(1, groups.size());
        var fixed = FixedScoreDemands.capture(specs, groups);
        assertTrue(fixed.matches(specs));
        other.getItems()[0].getOrCreateTag().putString("grade", "high");
        assertFalse(fixed.matches(specs));
        assertEquals(2, CandidateEngine.specDemands(specs).size());
    }

    @Test
    void frozenMachineReusesSelectionProbeOnlyWithinCurrentSlice() {
        ServerPlayer player = mock(ServerPlayer.class);
        ModType route = ModType.register("test_frozen_blast",
                new String[0], new String[0], new String[0], GenericBatchDelegate::new);
        var recipe = new BlastingRecipe(ResourceLocation.parse("test:frozen_blast"), "",
                CookingBookCategory.MISC, Ingredient.of(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT), 0, 100);
        var entry = new RecipeIndex.Entry(recipe, route, ResourceLocation.parse("minecraft:blasting"));
        var ctx = new ResolutionContext(null, Map.of(), List.of(), null, player, null);
        var handler = new VanillaMachineRecipeHandler();
        int[] probes = {0};
        try (var handlers = mockStatic(ModRecipeHandlers.class);
             var bindings = mockStatic(AltarBindingRegistry.class)) {
            handlers.when(() -> ModRecipeHandlers.claimFor(recipe))
                    .thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));
            bindings.when(() -> AltarBindingRegistry.hasBindingForRecipe(player, recipe, route))
                    .thenAnswer(call -> ++probes[0] == 1);
            ctx.beginCandidateSlice();
            assertEquals("", ctx.candidateRouteCheck(entry,
                    () -> AltarBindingRegistry.hasBindingForRecipe(player, recipe, route) ? "" : "unavailable"));
            assertTrue(CandidateEngine.frozenRouteStillAvailable(entry, ctx));
            assertEquals(1, probes[0]);
            ctx.beginCandidateSlice();
            assertFalse(CandidateEngine.frozenRouteStillAvailable(entry, ctx));
            assertEquals(2, probes[0]);
        }
    }

    @Test
    void previewRechecksUnavailableMachineOnTheNextSlice() {
        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ServerPlayer player = mock(ServerPlayer.class);
        ModType route = ModType.register("test_candidate_slice",
                new String[0], new String[0], new String[0], GenericBatchDelegate::new);
        ShapedRecipe recipe = new ShapedRecipe(ResourceLocation.parse("test:slice_binding"), "",
                CraftingBookCategory.MISC, 1, 1, NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STICK)),
                new ItemStack(Items.IRON_INGOT));
        var entry = new RecipeIndex.Entry(recipe, route, ResourceLocation.parse("test:machine"));
        int[] probes = {0};
        var config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        try (var index = mockStatic(RecipeIndex.class);
             var bindings = mockStatic(AltarBindingRegistry.class)) {
            index.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
            bindings.when(() -> AltarBindingRegistry.hasBindingForRecipe(player, recipe, route))
                    .thenAnswer(call -> ++probes[0] > 1);
            var ctx = new ResolutionContext(level, Map.of(Items.IRON_INGOT, List.of(entry)),
                    List.of(), null, player, null);
            ctx.deadlineNanos = Long.MAX_VALUE;
            Ingredient demand = Ingredient.of(Items.IRON_INGOT);
            ItemStack otherVariant = new ItemStack(Items.IRON_INGOT);
            otherVariant.getOrCreateTag().putString("grade", "other");
            ctx.beginCandidateSlice();
            assertTrue(CandidateEngine.findCandidates(StrictNBTIngredient.of(otherVariant), ctx).isEmpty());
            assertEquals(0, probes[0]);
            assertTrue(CandidateEngine.findCandidates(demand, ctx).isEmpty());
            assertTrue(CandidateEngine.findCandidates(demand, ctx).isEmpty());
            assertEquals(1, probes[0]);
            ctx.beginCandidateSlice();
            assertEquals(List.of(entry), CandidateEngine.findCandidates(demand, ctx));
            assertEquals(2, probes[0]);
        } finally {
            RSIntegrationConfig.SERVER_SPEC.setConfig(null);
        }
    }

    @Test
    void slashBladeOutputCheckDoesNotTreatEverySameItemVariantAsAProducer() {
        Ingredient required = ingredient("ak:rage", 1000, 10);
        var recipe = new ShapedRecipe(new ResourceLocation("test:blade"), "", CraftingBookCategory.MISC,
                1, 1, NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STICK)), blade("ak:rage", 1000, 10));
        var entry = new RecipeIndex.Entry(recipe, ModType.GENERIC, new ResourceLocation("minecraft:crafting"));
        assertTrue(CandidateEngine.passesOutputCheck(entry, blade("ak:rage", 1300, 12), required, true, false, null));
        assertFalse(CandidateEngine.passesOutputCheck(entry, blade("ak:greedy", 1300, 12), required, true, false, null));
        assertFalse(CandidateEngine.passesOutputCheck(entry, blade("ak:rage", 999, 12), required, true, false, null));
        assertFalse(CandidateEngine.passesOutputCheck(entry, new ItemStack(Items.IRON_SWORD), required, true, false, null));
    }

    @Test
    void typedSmithingCandidatesCarryTheModifiedWoodenSwordAcrossTwoUpgrades() throws Exception {
        var template = Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        var addition = Ingredient.of(Items.NETHERITE_INGOT);
        var stoneRecipe = new net.minecraft.world.item.crafting.SmithingTransformRecipe(
                new ResourceLocation("test:modified_stone"), template,
                Ingredient.of(Items.WOODEN_SWORD), addition, new ItemStack(Items.STONE_SWORD));
        var ironRecipe = new net.minecraft.world.item.crafting.SmithingTransformRecipe(
                new ResourceLocation("test:modified_iron"), template,
                Ingredient.of(Items.STONE_SWORD), addition, new ItemStack(Items.IRON_SWORD));
        ItemStack actual = new ItemStack(Items.WOODEN_SWORD);
        actual.setTag(net.minecraft.nbt.TagParser.parseTag(
                "{Unbreakable:1b,RepairCost:8,Damage:0,itemModifier:\"celestial_forge:vicious\"}"));
        var demand = net.minecraftforge.common.crafting.PartialNBTIngredient.of(Items.IRON_SWORD,
                net.minecraft.nbt.TagParser.parseTag("{Unbreakable:1}"));
        var index = Map.of(Items.STONE_SWORD, List.of(new RecipeIndex.Entry(stoneRecipe,
                ModType.GENERIC, new ResourceLocation("minecraft:smithing"))));
        ItemStack inherited = CandidateEngine.findStockedSmithingOutput(ironRecipe,
                stack -> IngredientMatcher.test(demand, stack),
                Map.of(new CraftingResolver.StackKey(actual.getItem(), actual.getTag().toString()), 1),
                index, net.minecraft.core.RegistryAccess.EMPTY, new java.util.HashSet<>(), () -> false);
        assertTrue(inherited.is(Items.IRON_SWORD));
        assertEquals(actual.getTag(), inherited.getTag());
        var pinned = com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler.requireDemandedOutputTag(
                ironRecipe, new com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler()
                        .getIngredients(ironRecipe), inherited);
        ItemStack stone = new ItemStack(Items.STONE_SWORD);
        stone.setTag(actual.getTag().copy());
        assertTrue(IngredientMatcher.test(pinned.get(1).ingredient(), stone));
        stone.getTag().putString("itemModifier", "celestial_forge:other");
        org.junit.jupiter.api.Assertions.assertFalse(IngredientMatcher.test(pinned.get(1).ingredient(), stone));
    }

    @Test
    void smithingCandidateInheritsDemandEvenWhenDeclaredResultContainsDefaultDamage() {
        ItemStack declared = new ItemStack(Items.STONE_SWORD);
        declared.getOrCreateTag().putInt("Damage", 0);
        var recipe = new net.minecraft.world.item.crafting.SmithingTransformRecipe(
                new ResourceLocation("minecraft:stone_sword_smithing"),
                Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                Ingredient.of(Items.WOODEN_SWORD), Ingredient.of(Items.STONE), declared);
        ItemStack required = new ItemStack(Items.STONE_SWORD);
        required.getOrCreateTag().putInt("Unbreakable", 1);
        Ingredient demand = net.minecraftforge.common.crafting.StrictNBTIngredient.of(required);

        ItemStack candidate = CandidateEngine.inheritSmithingBaseTag(recipe, declared, demand);

        assertTrue(IngredientMatcher.test(demand, candidate));
        assertTrue(candidate.getTag().getBoolean("Unbreakable"));
        assertEquals(0, declared.getTag().getInt("Unbreakable"));
    }

    @Test
    void timedOutScoringTailUsesNeutralDefaults() {
        ResourceLocation scored = new ResourceLocation("example", "scored");
        ResourceLocation timedOut = new ResourceLocation("example", "timed_out");
        Map<ResourceLocation, Integer> scores = new HashMap<>();
        Map<ResourceLocation, Integer> availability = new HashMap<>();
        scores.put(scored, 20);
        availability.put(scored, 1);

        int comparison = assertDoesNotThrow(() -> CandidateEngine.compareCandidateIds(
                scored, timedOut, scores, availability));

        assertTrue(comparison < 0);
    }

    @Test
    void equalScoreAndCoveragePreferFewerIndependentInputGroups() {
        ResourceLocation complex = new ResourceLocation("example", "a_complex");
        ResourceLocation simple = new ResourceLocation("example", "z_simple");
        Map<ResourceLocation, Integer> scores = Map.of(complex, 20, simple, 20);
        Map<ResourceLocation, Integer> availability = Map.of(complex, 0, simple, 0);
        Map<ResourceLocation, Integer> inputGroups = Map.of(complex, 9, simple, 2);

        int comparison = CandidateEngine.compareCandidateMetrics(
                simple, complex, scores, availability, inputGroups);

        assertTrue(comparison < 0);
    }

    @Test
    void equalScoreCoverageAndGroupsPreferFewerOperations() {
        // 同一份 810 mB 流体：粒每炉 10 mB（81 炉）、块每炉 1000 mB（1 炉）。
        ResourceLocation nuggets = new ResourceLocation("embers", "melting/nuggets/iron");
        ResourceLocation blocks = new ResourceLocation("embers", "melting/blocks/iron");
        Map<ResourceLocation, Integer> scores = Map.of(nuggets, 24, blocks, 24);
        Map<ResourceLocation, Integer> availability = Map.of(nuggets, 1, blocks, 1);
        Map<ResourceLocation, Integer> inputGroups = Map.of(nuggets, 1, blocks, 1);
        Map<ResourceLocation, Integer> operations = Map.of(nuggets, 81, blocks, 1);

        int comparison = CandidateEngine.compareCandidateMetrics(
                blocks, nuggets, scores, availability, inputGroups, operations);

        assertTrue(comparison < 0);
        assertTrue(CandidateEngine.compareCandidateMetrics(
                nuggets, blocks, scores, availability, inputGroups, operations) > 0);
    }

    @Test
    void stockedInputsOutrankFewerOperations() {
        // 库存差异先体现在分数上：块配方原料不在库存（分数低）时，
        // 不能因为「少烧几次」就去用库存里没有的原料，粒配方仍应优先。
        ResourceLocation nuggets = new ResourceLocation("embers", "melting/nuggets/iron");
        ResourceLocation blocks = new ResourceLocation("embers", "melting/blocks/iron");
        Map<ResourceLocation, Integer> scores = Map.of(nuggets, 24, blocks, 4);
        Map<ResourceLocation, Integer> availability = Map.of(nuggets, 1, blocks, 0);
        Map<ResourceLocation, Integer> inputGroups = Map.of(nuggets, 1, blocks, 1);
        Map<ResourceLocation, Integer> operations = Map.of(nuggets, 81, blocks, 1);

        int comparison = CandidateEngine.compareCandidateMetrics(
                nuggets, blocks, scores, availability, inputGroups, operations);

        assertTrue(comparison < 0);
    }

    @Test
    void operationEstimateRoundsUpAndIgnoresUnknownDemand() {
        assertEquals(0, CandidateEngine.estimatedOperations(0L, 10L));
        assertEquals(81, CandidateEngine.estimatedOperations(810L, 10L));
        assertEquals(1, CandidateEngine.estimatedOperations(810L, 1000L));
        assertEquals(1, CandidateEngine.estimatedOperations(10L, 10L));
        assertEquals(Integer.MAX_VALUE, CandidateEngine.estimatedOperations(810L, 0L));
    }

    @Test
    void repeatedCraftingSlotsRequireTheirFullQuantity() {
        ShapedRecipe recipe = new ShapedRecipe(
                new ResourceLocation("test", "four_iron"), "", CraftingBookCategory.MISC,
                2, 2, NonNullList.withSize(4, Ingredient.of(Items.IRON_INGOT)),
                new ItemStack(Items.IRON_BLOCK));

        assertEquals(1, CandidateEngine.craftingDemands(recipe).size());
        assertEquals(4, CandidateEngine.craftingDemands(recipe).values().iterator().next().required());
    }

    @Test
    void genericOnlyResolutionSkipsVirtualMachineCandidates() {
        ShapedRecipe recipe = new ShapedRecipe(
                new ResourceLocation("test", "virtual_iron_block"), "", CraftingBookCategory.MISC,
                1, 1, NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)),
                new ItemStack(Items.IRON_BLOCK));
        RecipeIndex.Entry virtual = new RecipeIndex.Entry(
                recipe, ModType.FLUID_CONTAINER,
                new ResourceLocation("test", "virtual_machine"));
        ResolutionContext context = new ResolutionContext(
                null, Map.of(Items.IRON_BLOCK, List.of(virtual)), List.of(), null)
                .genericCandidatesOnly();

        assertTrue(CandidateEngine.findCandidates(Ingredient.of(Items.IRON_BLOCK), context).isEmpty());
    }

    @Test
    void sameIngredientCatalystAndConsumedDemandStaySeparate() {
        Ingredient iron = Ingredient.of(Items.IRON_INGOT);
        Map<String, CandidateEngine.IngredientDemand> demands = CandidateEngine.specDemands(List.of(
                new IngredientSpec(iron, 1, DemandRole.CATALYST),
                new IngredientSpec(iron, 1, DemandRole.CATALYST),
                new IngredientSpec(iron, 1, DemandRole.CONSUMED)));

        assertEquals(2, demands.size());
        assertTrue(demands.values().stream().anyMatch(d -> d.required() == 2
                && d.role() == DemandRole.CATALYST));
        assertTrue(demands.values().stream().anyMatch(d -> d.required() == 1
                && d.role() == DemandRole.CONSUMED));
    }

    @Test
    void cycleGuardDoesNotTreatOrIngredientVariantsAsConsumedInputs() {
        assertEquals(1, CraftingResolver.cycleGuardInputKeys(
                Ingredient.of(Items.QUARTZ_BLOCK)).size());
        assertTrue(CraftingResolver.cycleGuardInputKeys(
                Ingredient.of(Items.CHISELED_QUARTZ_BLOCK, Items.QUARTZ_BLOCK)).isEmpty());
    }
}
