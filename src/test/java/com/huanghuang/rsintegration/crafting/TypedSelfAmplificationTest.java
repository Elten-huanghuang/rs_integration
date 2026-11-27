package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.RSICraftingMaterialSources;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingResolver.EdgeTracker;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyRSModule;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ModIds;
import com.rekindled.embers.recipe.AlchemyRecipe;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class TypedSelfAmplificationTest extends BootstrapTest {

    @BeforeAll
    static void loadConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading =
                     mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            RSIntegrationMod.MOD_BUS.getClass();
        }
        EreAlchemyRSModule.INSTANCE.registerModType();
    }

    @Test
    void productiveSelfEdgeDoesNotBlockTheSameCopyRecipeInAnotherBranch() {
        EdgeTracker edges = new EdgeTracker();
        StackKey seed = new StackKey(Items.DIAMOND, null);
        StackKey fuel = new StackKey(Items.COAL, null);
        edges.beginUndo();
        edges.addEdge(seed, seed);
        edges.addEdge(fuel, seed);
        edges.commitUndo();

        assertFalse(edges.containsReverse(seed, seed));
        assertTrue(edges.containsReverse(seed, fuel));
    }

    @Test
    void strictGridSearchCanBuyTheRolledBackIngredientWithoutAMachineOrNetwork() {
        CraftingRecipe gear = craftingRecipe("gear", Items.COMPASS, 1, Items.IRON_INGOT);
        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ServerPlayer player = mock(ServerPlayer.class);
        var iron = new StackKey(Items.IRON_INGOT, null);
        var id = ResourceLocation.parse("test:iron");
        var pool = new RegisteredMaterialSources.Pool(id, id);
        var stock = new RegisteredMaterialSources.StockSnapshot(Map.of(), Map.of(iron, List.of(
                new RegisteredMaterialSources.Stock(id, iron, pool, BigInteger.ZERO, 1))));
        var selection = new MaterialSourceSelection(false, false, true);
        try (var scope = selection.enter(); var index = mockStatic(RecipeIndex.class);
             var sources = mockStatic(RegisteredMaterialSources.class);
             var config = mockStatic(RSIntegrationConfig.class, CALLS_REAL_METHODS)) {
            config.when(() -> RSIntegrationConfig.enabled(RSIntegrationConfig.COMMON_SPEC,
                    RSIntegrationConfig.ENABLE_MULTIBLOCK_AUTO_CRAFTING)).thenReturn(false);
            index.when(() -> RecipeIndex.get(level)).thenReturn(Map.of(Items.COMPASS, List.of(craftingEntry(gear))));
            sources.when(() -> RegisteredMaterialSources.hasEnabled(selection)).thenReturn(true);
            sources.when(() -> RegisteredMaterialSources.capture(player, null, selection, Set.of(),
                    RSICraftingMaterialSources.Phase.REVALIDATE, null, null)).thenReturn(stock);
            var missing = new ArrayList<String>();
            var inputs = List.of(new IngredientSpec(Ingredient.of(Items.COMPASS), 1));
            var graph = CraftingResolver.resolveGraphForSpecsWithTypes(inputs, Map.of(), level, player, null,
                    missing, null, false, 10_000, null, Map.of(), PlanningPreferences.empty());
            assertTrue(missing.isEmpty(), missing.toString());
            assertEquals(List.of(gear.getId()), graph.nodes().stream().map(node -> node.recipeId()).toList());
            assertEquals(Map.of(MaterialKey.of(iron.toStack()), Map.of(id, 1L)), InitialMaterialPlan.from(graph).sourceShares());

            var physical = CraftingResolver.resolveGraphForSpecsWithTypes(inputs, Map.of(iron, 1L), level,
                    player, null, missing, null, false, 10_000, null, Map.of(), PlanningPreferences.empty());
            assertTrue(missing.isEmpty());
            assertTrue(InitialMaterialPlan.from(physical).sourceShares().isEmpty());

            var disabled = new PlanningPreferences(Map.of(), Map.of(), Set.of(), MaterialSourceSelection.fromMask(0));
            CraftingResolver.resolveGraphForSpecsWithTypes(inputs, Map.of(), level, player, null,
                    missing, null, false, 10_000, null, Map.of(), disabled);
            assertFalse(missing.isEmpty());
            sources.verify(() -> RegisteredMaterialSources.capture(player, null, selection, Set.of(),
                    RSICraftingMaterialSources.Phase.REVALIDATE, null, null));
        }
    }

    @Test
    void siblingRootsMayUnpackAndRepackWithAdditionalStock() {
        CraftingRecipe unpack = craftingRecipe("a_unpack", Items.IRON_INGOT, 9, Items.IRON_BLOCK);
        CraftingRecipe smelt = craftingRecipe("z_smelt", Items.IRON_INGOT, 1, Items.RAW_IRON);
        CraftingRecipe pack = craftingRecipe("pack", Items.IRON_BLOCK, 1,
                Items.IRON_INGOT, Items.IRON_INGOT, Items.IRON_INGOT,
                Items.IRON_INGOT, Items.IRON_INGOT, Items.IRON_INGOT,
                Items.IRON_INGOT, Items.IRON_INGOT, Items.IRON_INGOT);
        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        var missing = new ArrayList<String>();
        try (var index = mockStatic(RecipeIndex.class)) {
            index.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
            index.when(() -> RecipeIndex.get(level)).thenReturn(Map.of(
                    Items.IRON_INGOT, List.of(craftingEntry(unpack), craftingEntry(smelt)),
                    Items.IRON_BLOCK, List.of(craftingEntry(pack))));

            CraftPlanGraph graph = CraftingResolver.resolveMachineGraphForSpecsWithTypes(
                    List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1),
                            new IngredientSpec(Ingredient.of(Items.IRON_BLOCK), 1)),
                    Map.of(new StackKey(Items.IRON_BLOCK, null), 1L,
                            new StackKey(Items.RAW_IRON, null), 1L),
                    level, null, null, missing, null, false, 10_000);

            CraftPlanValidator.validate(graph);
            assertEquals(List.of(unpack.getId(), smelt.getId(), pack.getId()),
                    graph.nodes().stream().map(node -> node.recipeId()).toList());
            assertTrue(missing.isEmpty(), missing.toString());
            assertTrue(graph.unresolvedDemands().isEmpty());
            assertEquals(0L, graph.rootDemands().stream().mapToLong(root -> root.unresolvedQuantity()).sum());
        }
    }

    @Test
    void typedPreviewTriesOtherMachineVariantsBeforeReturningMissing() {
        var makeGold = fixedRecipe("variant_gold", Items.RAW_GOLD, Items.GOLD_INGOT, 1);
        var graph = restockGraph(new IngredientSpec(Ingredient.of(Items.IRON_INGOT, Items.GOLD_INGOT), 4),
                Map.of(Items.GOLD_INGOT, List.of(craftingEntry(makeGold))),
                Map.of(new StackKey(Items.IRON_INGOT, null), 1L, new StackKey(Items.RAW_GOLD, null), 4L), null);
        assertEquals(0L, graph.rootDemands().get(0).unresolvedQuantity());
        assertEquals(List.of(makeGold.getId()), graph.nodes().stream().map(node -> node.recipeId()).toList());
        assertTrue(graph.unresolvedDemands().isEmpty());
    }

    @Test
    void failedUnpackNamesItsInputButDoesNotHideTheWorkingRoute() {
        var unpack = fixedRecipe("a_unpack", Items.EMERALD_BLOCK, Items.EMERALD, 9);
        var infuse = fixedRecipe("z_infuse", Items.DIAMOND, Items.EMERALD, 1);
        var precursor = fixedRecipe("make_precursor", Items.COAL, Items.DIAMOND, 1);
        var level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        try (var index = mockStatic(RecipeIndex.class)) {
            index.when(() -> RecipeIndex.get(level)).thenReturn(Map.of(
                    Items.EMERALD, List.of(craftingEntry(unpack), craftingEntry(infuse)),
                    Items.DIAMOND, List.of(craftingEntry(precursor))));
            var need = ScaledIngredientSpec.of(new IngredientSpec(Ingredient.of(Items.EMERALD), 1), 1);
            var job = CraftingResolver.startTypedPreview(List.of(need), Map.of(new StackKey(Items.COAL, null), 1L),
                    level, null, null, new ArrayList<>(), null, true, 10_000, null, Map.of(), PlanningPreferences.empty());
            assertTrue(job.advance(Long.MAX_VALUE));
            String report = job.timeoutSummary();
            assertTrue(report.contains("test:a_unpack/input[0]:minecraft:emerald_block=1"), report);
            var graph = job.graph();
            assertEquals(List.of(precursor.getId(), infuse.getId()), graph.nodes().stream().map(n -> n.recipeId()).toList());
            assertTrue(graph.unresolvedDemands().isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"7,1,0,0", "7,0,0,1", "6,2,0,8", "0,0,11,53", "0,0,0,64"})
    void restockPreviewUsesRealSupplyAndStopsAtTheRemainingIron(int blocks, int ore, int ingots, int missing) {
        var unpack = fixedRecipe("unpack_iron", Items.IRON_BLOCK, Items.IRON_INGOT, 9);
        var smelt = fixedRecipe("smelt_iron", Items.RAW_IRON, Items.IRON_INGOT, 1);
        var graph = restockGraph(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 64),
                Map.of(Items.IRON_INGOT, List.of(craftingEntry(unpack), craftingEntry(smelt))),
                Map.of(new StackKey(Items.IRON_BLOCK, null), (long) blocks,
                        new StackKey(Items.IRON_INGOT, null), (long) ingots,
                        new StackKey(Items.RAW_IRON, null), (long) ore), null);
        var root = graph.rootDemands().get(0);
        assertEquals(missing, root.unresolvedQuantity());
        assertEquals(64L - missing, root.allocations().stream().mapToLong(a -> a.quantity()).sum());
        assertTrue(graph.unresolvedDemands().isEmpty());
        assertEquals(blocks + ore, graph.nodes().stream().mapToInt(node -> node.executions()).sum());
    }

    @Test
    void unseededCopyDoesNotStartATransactionAndCanRunOnceSeeded() {
        var copy = fixedRecipe("copy_compass", Items.COMPASS, Items.COMPASS, 2);
        var level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        try (var index = mockStatic(RecipeIndex.class)) {
            index.when(() -> RecipeIndex.get(level)).thenReturn(Map.of(Items.COMPASS, List.of(craftingEntry(copy))));
            var need = ScaledIngredientSpec.of(new IngredientSpec(Ingredient.of(Items.COMPASS), 1), 4);
            for (long seeds : List.of(0L, 1L)) {
                var job = CraftingResolver.startTypedPreview(List.of(need), Map.of(new StackKey(Items.COMPASS, null), seeds),
                        level, null, null, new ArrayList<>(), null, true, 10_000, null, Map.of(), PlanningPreferences.empty());
                assertTrue(job.advance(Long.MAX_VALUE));
                var graph = job.graph();
                assertEquals(seeds == 0 ? 4L : 0L, graph.rootDemands().get(0).unresolvedQuantity());
                if (seeds == 0) {
                    assertTrue(job.timeoutSummary().contains("tried=0,rollback=0"), job.timeoutSummary());
                    assertTrue(graph.nodes().isEmpty());
                } else assertFalse(graph.nodes().isEmpty());
            }
        }
    }

    @Test
    void missingPreviewRanksTheRequestedBatchAndRespectsRecipeLock() {
        var rare = fixedRecipe("a_rare", Items.DIAMOND, Items.COMPASS, 1);
        var common = new ShapelessRecipe(ResourceLocation.parse("test:z_common"), "", CraftingBookCategory.MISC,
                new ItemStack(Items.COMPASS), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.COAL), Ingredient.of(Items.COAL)));
        var recipes = Map.of(Items.COMPASS, List.of(craftingEntry(rare), craftingEntry(common)));
        var root = new IngredientSpec(Ingredient.of(Items.COMPASS), 10);
        // 原料总共最多供 9 件，两条路线都不可能满足整单。
        var stock = Map.of(new StackKey(Items.DIAMOND, null), 1L, new StackKey(Items.COAL, null), 16L);
        var plan = restockGraph(root, recipes, stock, null);
        assertEquals(List.of(common.getId()), plan.nodes().stream().map(n -> n.recipeId()).toList());
        assertEquals(4L, plan.unresolvedDemands().stream().mapToLong(d -> d.quantity()).sum());
        var locked = restockGraph(root, recipes, stock, Map.of(ResourceLocation.parse("minecraft:compass"), rare.getId()));
        assertEquals(List.of(rare.getId()), locked.nodes().stream().map(n -> n.recipeId()).toList());
        assertEquals(9L, locked.unresolvedDemands().stream().mapToLong(d -> d.quantity()).sum());
    }

    @Test
    void lockedRestockRecipeStillShowsItsOwnMissingInput() {
        var unpack = fixedRecipe("locked_unpack", Items.IRON_BLOCK, Items.IRON_INGOT, 9);
        var graph = restockGraph(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 9),
                Map.of(Items.IRON_INGOT, List.of(craftingEntry(unpack))), Map.of(),
                Map.of(new ResourceLocation("minecraft", "iron_ingot"), unpack.getId()));
        assertEquals(List.of(unpack.getId()), graph.nodes().stream().map(node -> node.recipeId()).toList());
        assertEquals(1L, graph.unresolvedDemands().get(0).quantity());
        assertTrue(graph.unresolvedDemands().get(0).ingredient().test(new ItemStack(Items.IRON_BLOCK)));
    }

    private static ShapelessRecipe fixedRecipe(String name, Item input, Item output, int count) {
        return new ShapelessRecipe(new ResourceLocation("test", name), "", CraftingBookCategory.MISC,
                new ItemStack(output, count), NonNullList.of(Ingredient.EMPTY, Ingredient.of(input)));
    }

    private static CraftPlanGraph restockGraph(IngredientSpec root, Map<Item, List<RecipeIndex.Entry>> entries,
                                                Map<StackKey, Long> stock, Map<ResourceLocation, ResourceLocation> locks) {
        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        try (var index = mockStatic(RecipeIndex.class)) {
            index.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
            index.when(() -> RecipeIndex.get(level)).thenReturn(entries);
            var graph = CraftingResolver.resolveMachineGraphForSpecsWithTypes(List.of(root), stock, level,
                    null, null, new ArrayList<>(), locks, true, 10_000);
            CraftPlanValidator.validate(graph);
            return graph;
        }
    }

    @Test
    void sharedSeedProbeYieldsBetweenCandidateRecipes() {
        var probes = new AtomicInteger();
        List<RecipeIndex.Entry> candidates = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            CraftingRecipe recipe = craftingRecipe("noncopy_" + i, Items.DIAMOND, 1, Items.COAL);
            when(recipe.getResultItem(any())).thenAnswer(call -> {
                probes.incrementAndGet();
                return new ItemStack(Items.DIAMOND);
            });
            candidates.add(craftingEntry(recipe));
        }
        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        Ingredient seed = Ingredient.of(Items.DIAMOND);
        try (var index = mockStatic(RecipeIndex.class)) {
            index.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
            var context = new ResolutionContext(level, Map.of(Items.DIAMOND, candidates),
                    Map.of(new StackKey(Items.DIAMOND, null), 1L), null, false, new ArrayList<>(),
                    Long.MAX_VALUE, false);
            context.pendingInputs.push(List.of(ScaledIngredientSpec.of(new IngredientSpec(seed, 1), 1)));
            var stack = new PlanningStack();
            var job = stack.<Boolean>start(done -> CraftingResolver.ensureIngredient(
                    seed, 1, context, 0, new EdgeTracker(), null, null, stack, done));

            boolean complete;
            do {
                int before = probes.get();
                complete = job.advance(0L);
                assertTrue(probes.get() - before <= 1);
            } while (!complete);

            assertTrue(job.result());
            assertEquals(candidates.size(), probes.get());
            assertTrue(context.graphNodes.isEmpty());
        }
    }

    @Test
    void fluidMixingRemainsAvailableAsAnIntermediateForIngotCrafting() {
        // 同一物品上的不同 NBT 分别代表铜液、金液和黎明石液。
        ItemStack copper = new ItemStack(Items.PAPER);
        copper.getOrCreateTag().putString("FluidName", "embers:molten_copper");
        copper.getTag().putInt("Amount", 1);
        ItemStack gold = copper.copy();
        gold.getTag().putString("FluidName", "embers:molten_gold");
        ItemStack dawnstone = copper.copyWithCount(4);
        dawnstone.getTag().putString("FluidName", "embers:molten_dawnstone");
        Ingredient copperInput = StrictNBTIngredient.of(copper);
        Ingredient goldInput = StrictNBTIngredient.of(gold);
        Ingredient dawnstoneInput = StrictNBTIngredient.of(dawnstone.copyWithCount(1));
        ShapelessRecipe mixer = new ShapelessRecipe(
                new ResourceLocation("embers", "mixing/molten_dawnstone"), "",
                CraftingBookCategory.MISC, dawnstone,
                NonNullList.of(Ingredient.EMPTY, copperInput, copperInput, goldInput, goldInput));
        ShapelessRecipe stamper = new ShapelessRecipe(
                new ResourceLocation("embers", "stamping/ingots/dawnstone"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.GOLD_INGOT),
                NonNullList.of(Ingredient.EMPTY,
                        dawnstoneInput, dawnstoneInput, dawnstoneInput, dawnstoneInput));
        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        List<String> missing = new ArrayList<>();
        try (MockedStatic<RecipeIndex> recipeIndex = mockStatic(RecipeIndex.class)) {
            recipeIndex.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
            recipeIndex.when(() -> RecipeIndex.get(level)).thenReturn(Map.of(
                    Items.PAPER, List.of(craftingEntry(mixer)),
                    Items.GOLD_INGOT, List.of(craftingEntry(stamper))));

            CraftPlanGraph graph = CraftingResolver.resolveMachineGraphForSpecsWithTypes(
                    List.of(new IngredientSpec(Ingredient.of(Items.GOLD_INGOT), 2)),
                    Map.of(StackKey.of(copper, true), 4L, StackKey.of(gold, true), 4L),
                    level, null, null, missing, null, false, 10_000);

            CraftPlanValidator.validate(graph);
            assertTrue(missing.isEmpty(), missing.toString());
            assertTrue(graph.unresolvedDemands().isEmpty());
            assertEquals(2, graph.nodes().stream()
                    .filter(node -> node.recipeId().equals(mixer.getId()))
                    .mapToInt(node -> node.executions()).sum());
            assertEquals(2, graph.nodes().stream()
                    .filter(node -> node.recipeId().equals(stamper.getId()))
                    .mapToInt(node -> node.executions()).sum());
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 4, false, false", "4, 1, false, false", "1, 4, true, false",
            "1, 5, false, true", "1, 5, true, true", "0, 16, false, false",
            "1, 3, false, false"})
    void copySeedSurvivesAnIntermediateBeforeALaterRootDemand(
            int seeds, int fuel, boolean bestEffort, boolean forced) {
        CraftingRecipe copy = craftingRecipe("copy_calx", Items.DIAMOND, 2,
                Items.DIAMOND, Items.COAL);
        CraftingRecipe glass = craftingRecipe("alchemy_glass", Items.REDSTONE, 1,
                Items.DIAMOND, Items.DIAMOND, Items.DIAMOND, Items.DIAMOND);
        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ArrayList<String> missing = new ArrayList<>();
        try (MockedStatic<RecipeIndex> recipeIndex = mockStatic(RecipeIndex.class)) {
            recipeIndex.when(() -> RecipeIndex.reusableCatalystRoutes(any()))
                    .thenReturn(Map.of());
            recipeIndex.when(() -> RecipeIndex.get(level)).thenReturn(Map.of(
                    Items.DIAMOND, List.of(craftingEntry(copy)),
                    Items.REDSTONE, List.of(craftingEntry(glass))));

            CraftPlanGraph graph = CraftingResolver.resolveMachineGraphForSpecsWithTypes(
                    List.of(new IngredientSpec(Ingredient.of(Items.REDSTONE), 1),
                            new IngredientSpec(Ingredient.of(Items.DIAMOND), 1)),
                    Map.of(new StackKey(Items.DIAMOND, null), (long) seeds,
                            new StackKey(Items.COAL, null), (long) fuel),
                    level, null, null, missing,
                    forced ? Map.of(new ResourceLocation("minecraft", "diamond"), copy.getId()) : null,
                    bestEffort, 10_000);

            CraftPlanValidator.validate(graph);
            boolean feasible = seeds > 0 && seeds + fuel >= (forced ? 6 : 5);
            assertEquals(feasible, missing.isEmpty(), missing.toString());
            assertEquals(feasible, graph.unresolvedDemands().isEmpty()
                    && graph.rootDemands().size() == 2
                    && graph.rootDemands().stream().allMatch(root -> root.unresolvedQuantity() == 0));
            if (feasible) {
                assertEquals(fuel, graph.nodes().stream()
                        .filter(node -> node.recipeId().equals(copy.getId()))
                        .mapToInt(node -> node.executions()).sum());
            }
        }
    }

    private static CraftingRecipe craftingRecipe(String id, Item output,
                                                  int count, Item... inputs) {
        CraftingRecipe recipe = mock(CraftingRecipe.class);
        when(recipe.getId()).thenReturn(new ResourceLocation("test", id));
        NonNullList<Ingredient> ingredients = NonNullList.create();
        for (var input : inputs) ingredients.add(Ingredient.of(input));
        when(recipe.getIngredients()).thenReturn(ingredients);
        when(recipe.getRemainingItems(any())).thenReturn(NonNullList.create());
        when(recipe.getResultItem(any())).thenReturn(new ItemStack(output, count));
        return recipe;
    }

    private static RecipeIndex.Entry craftingEntry(CraftingRecipe recipe) {
        return new RecipeIndex.Entry(recipe, ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"), false);
    }

    @Test
    void oneSeedCanSatisfyMultipleTypedEngineBatches() {
        ArrayList<Ingredient> inputs = new ArrayList<>();
        inputs.add(Ingredient.of(Items.SOUL_SAND));
        inputs.add(Ingredient.of(Items.CLAY_BALL));
        AlchemyRecipe recipe = new AlchemyRecipe(
                new ResourceLocation("embers", "alchemy/test_amplification"),
                Ingredient.of(Items.DIAMOND),
                new ArrayList<>(List.of(Ingredient.of(Items.COAL))),
                inputs,
                new ItemStack(Items.DIAMOND, 5),
                ItemStack.EMPTY);
        RecipeIndex.Entry entry = new RecipeIndex.Entry(
                recipe, ModType.byId(ModIds.ID_EMBERS_ALCHEMY),
                new ResourceLocation("embers", "alchemy"), false);

        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ServerPlayer player = mock(ServerPlayer.class);
        ArrayList<String> missing = new ArrayList<>();

        try (MockedStatic<AltarBindingRegistry> bindings =
                     mockStatic(AltarBindingRegistry.class);
             MockedStatic<RecipeIndex> recipeIndex =
                     mockStatic(RecipeIndex.class)) {
            recipeIndex.when(() -> RecipeIndex.reusableCatalystRoutes(any()))
                    .thenReturn(Map.of());
            ResolutionContext context = new ResolutionContext(
                    level, Map.of(Items.DIAMOND, List.of(entry)),
                    Map.of(new StackKey(Items.DIAMOND, null), 1L,
                            new StackKey(Items.SOUL_SAND, null), 8L,
                            new StackKey(Items.CLAY_BALL, null), 8L),
                    null, null, player, null, false, missing,
                    Long.MAX_VALUE, false)
                    .withPreferences(PlanningPreferences.empty());
            bindings.when(() -> AltarBindingRegistry.hasBindingForRecipe(
                    any(), any(), any())).thenReturn(true);
            assertTrue(CraftingResolver.ensureIngredient(
                    Ingredient.of(Items.DIAMOND), 8, context, 0, new EdgeTracker()), missing.toString());
        }
    }

    @Test
    void oneSeedCanFeedTwoTypedSiblingBranches() {
        AlchemyRecipe brickRecipe = new AlchemyRecipe(
                new ResourceLocation("embers", "alchemy/test_brick"),
                Ingredient.of(Items.DIAMOND),
                new ArrayList<>(List.of(Ingredient.of(Items.COAL))),
                new ArrayList<>(List.of(
                        Ingredient.of(Items.SOUL_SAND), Ingredient.of(Items.CLAY_BALL))),
                new ItemStack(Items.DIAMOND, 5),
                ItemStack.EMPTY);
        CraftingRecipe circuitRecipe = mock(CraftingRecipe.class);
        when(circuitRecipe.getId())
                .thenReturn(new ResourceLocation("test", "circuit"));
        when(circuitRecipe.getIngredients()).thenReturn(NonNullList.of(
                Ingredient.EMPTY, Ingredient.of(Items.DIAMOND)));
        when(circuitRecipe.getRemainingItems(any())).thenReturn(NonNullList.create());
        when(circuitRecipe.getResultItem(any()))
                .thenReturn(new ItemStack(Items.REDSTONE));
        AlchemyRecipe apparatusRecipe = new AlchemyRecipe(
                new ResourceLocation("embers", "alchemy/test_apparatus"),
                Ingredient.of(Items.IRON_INGOT),
                new ArrayList<>(List.of(Ingredient.of(Items.COAL))),
                new ArrayList<>(List.of(
                        Ingredient.of(Items.GOLD_INGOT),
                        Ingredient.of(Items.REDSTONE),
                        Ingredient.of(Items.GOLD_INGOT),
                        Ingredient.of(Items.REDSTONE))),
                new ItemStack(Items.EMERALD),
                ItemStack.EMPTY);
        RecipeIndex.Entry brickEntry = new RecipeIndex.Entry(
                brickRecipe, ModType.byId(ModIds.ID_EMBERS_ALCHEMY),
                new ResourceLocation("embers", "alchemy"), false);
        RecipeIndex.Entry circuitEntry = new RecipeIndex.Entry(
                circuitRecipe, ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"), false);
        RecipeIndex.Entry apparatusEntry = new RecipeIndex.Entry(
                apparatusRecipe, ModType.byId(ModIds.ID_EMBERS_ALCHEMY),
                new ResourceLocation("embers", "alchemy"), false);

        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ServerPlayer player = mock(ServerPlayer.class);
        ArrayList<String> missing = new ArrayList<>();
        try (MockedStatic<AltarBindingRegistry> bindings =
                     mockStatic(AltarBindingRegistry.class);
             MockedStatic<RecipeIndex> recipeIndex =
                     mockStatic(RecipeIndex.class)) {
            recipeIndex.when(() -> RecipeIndex.reusableCatalystRoutes(any()))
                    .thenReturn(Map.of());
            bindings.when(() -> AltarBindingRegistry.hasBindingForRecipe(
                    any(), any(), any())).thenReturn(true);
            ResolutionContext context = new ResolutionContext(
                    level, Map.of(
                            Items.DIAMOND, List.of(brickEntry),
                            Items.REDSTONE, List.of(circuitEntry),
                            Items.EMERALD, List.of(apparatusEntry)),
                    Map.of(new StackKey(Items.DIAMOND, null), 1L,
                            new StackKey(Items.SOUL_SAND, null), 8L,
                            new StackKey(Items.CLAY_BALL, null), 16L,
                            new StackKey(Items.IRON_INGOT, null), 1L,
                            new StackKey(Items.GOLD_INGOT, null), 2L),
                    null, null, player, null, false, missing,
                    Long.MAX_VALUE, false)
                    .withPreferences(PlanningPreferences.empty());

            assertTrue(CraftingResolver.ensureIngredient(
                    Ingredient.of(Items.EMERALD), 1, context, 0, new EdgeTracker()), missing.toString());
        }
    }

    @Test
    void oneBrickSeedCanCraftTheFourApparatusNeededByTacz() {
        AlchemyRecipe brickRecipe = new AlchemyRecipe(
                new ResourceLocation("embers", "alchemy/test_brick_for_tacz"),
                Ingredient.of(Items.DIAMOND),
                new ArrayList<>(List.of(Ingredient.of(Items.COAL))),
                new ArrayList<>(List.of(
                        Ingredient.of(Items.SOUL_SAND),
                        Ingredient.of(Items.CLAY_BALL))),
                new ItemStack(Items.DIAMOND, 5),
                ItemStack.EMPTY);
        CraftingRecipe circuitRecipe = mock(CraftingRecipe.class);
        when(circuitRecipe.getId())
                .thenReturn(new ResourceLocation("test", "circuit_for_tacz"));
        when(circuitRecipe.getIngredients()).thenReturn(NonNullList.of(
                Ingredient.EMPTY,
                Ingredient.of(Items.DIAMOND),
                Ingredient.of(Items.DIAMOND),
                Ingredient.of(Items.DIAMOND),
                Ingredient.of(Items.DIAMOND),
                Ingredient.of(Items.COPPER_INGOT)));
        when(circuitRecipe.getRemainingItems(any())).thenReturn(NonNullList.create());
        when(circuitRecipe.getResultItem(any()))
                .thenReturn(new ItemStack(Items.REDSTONE));
        AlchemyRecipe apparatusRecipe = new AlchemyRecipe(
                new ResourceLocation("embers", "alchemy/test_apparatus_for_tacz"),
                Ingredient.of(Items.COPPER_INGOT),
                new ArrayList<>(List.of(Ingredient.of(Items.COAL))),
                new ArrayList<>(List.of(
                        Ingredient.of(Items.COPPER_INGOT),
                        Ingredient.of(Items.REDSTONE),
                        Ingredient.of(Items.COPPER_INGOT),
                        Ingredient.of(Items.REDSTONE))),
                new ItemStack(Items.EMERALD, 4),
                ItemStack.EMPTY);
        RecipeIndex.Entry brickEntry = new RecipeIndex.Entry(
                brickRecipe, ModType.byId(ModIds.ID_EMBERS_ALCHEMY),
                new ResourceLocation("embers", "alchemy"), false);
        RecipeIndex.Entry circuitEntry = new RecipeIndex.Entry(
                circuitRecipe, ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"), false);
        RecipeIndex.Entry apparatusEntry = new RecipeIndex.Entry(
                apparatusRecipe, ModType.byId(ModIds.ID_EMBERS_ALCHEMY),
                new ResourceLocation("embers", "alchemy"), false);

        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ServerPlayer player = mock(ServerPlayer.class);
        ArrayList<String> missing = new ArrayList<>();
        try (MockedStatic<AltarBindingRegistry> bindings =
                     mockStatic(AltarBindingRegistry.class);
             MockedStatic<RecipeIndex> recipeIndex =
                     mockStatic(RecipeIndex.class)) {
            recipeIndex.when(() -> RecipeIndex.reusableCatalystRoutes(any()))
                    .thenReturn(Map.of());
            bindings.when(() -> AltarBindingRegistry.hasBindingForRecipe(
                    any(), any(), any())).thenReturn(true);
            ResolutionContext context = new ResolutionContext(
                    level, Map.of(
                            Items.DIAMOND, List.of(brickEntry),
                            Items.REDSTONE, List.of(circuitEntry),
                            Items.EMERALD, List.of(apparatusEntry)),
                    Map.of(new StackKey(Items.DIAMOND, null), 1L,
                            new StackKey(Items.SOUL_SAND, null), 2L,
                            new StackKey(Items.CLAY_BALL, null), 4L,
                            new StackKey(Items.COPPER_INGOT, null), 5L),
                    null, null, player, null, false, missing,
                    Long.MAX_VALUE, false)
                    .withPreferences(PlanningPreferences.empty());

            assertTrue(CraftingResolver.ensureIngredient(
                    Ingredient.of(Items.EMERALD), 4, context, 0,
                    new EdgeTracker()), missing.toString());
        }
    }
}
