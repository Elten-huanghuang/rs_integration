package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.crafting.plan.ParallelPlanEstimator;
import com.huanghuang.rsintegration.mods.embers.EmbersStamperBatchDelegate;
import com.huanghuang.rsintegration.mods.embers.EmbersStampingRecipeHandler;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import com.rekindled.embers.recipe.FluidIngredient;
import com.rekindled.embers.recipe.IStampingRecipe;
import net.minecraft.core.NonNullList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

public final class DeepPlanningFixture implements AutoCloseable {
    private final Level level = mock(Level.class, withSettings().stubOnly());
    private final Map<Item, List<RecipeIndex.Entry>> index = new HashMap<>();
    private final ImmutableRecipeGraph graph;
    private final List<ScaledIngredientSpec> demands;
    private final List<IngredientRef> pureDemands;
    private final Item firstRoot;
    private final MockedStatic<RecipeIndex> recipes;
    private final long stock;
    private NativeRoutes nativeRoutes;

    public static void initializeMod() {
        var context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            RSIntegrationMod.MOD_BUS.getClass();
        }
    }

    public DeepPlanningFixture(int depth, int branches, boolean shared) {
        this(depth, branches, shared, false);
    }

    public DeepPlanningFixture(int depth, int branches, boolean shared, boolean mixed) {
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        var items = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR && item != Items.STICK)
                .limit((long) depth * (shared ? 1 : branches) + branches).toList().iterator();
        Map<MaterialRef, List<RecipeNode>> outputs = new HashMap<>();
        Item sharedOutput = Items.STICK;
        if (shared) {
            for (int i = 0; i < depth; i++) sharedOutput = addRecipe(outputs, items.next(), sharedOutput, mixed);
        }
        List<Item> roots = new ArrayList<>();
        for (int branch = 0; branch < branches; branch++) {
            Item output = sharedOutput;
            for (int i = 0; i < (shared ? 1 : depth); i++) {
                output = addRecipe(outputs, items.next(), output, mixed);
            }
            roots.add(output);
        }
        demands = roots.stream().map(item -> ScaledIngredientSpec.of(
                new IngredientSpec(Ingredient.of(item), 1), 1)).toList();
        firstRoot = roots.get(0);
        pureDemands = roots.stream().map(DeepPlanningFixture::need).toList();
        graph = new ImmutableRecipeGraph(outputs);
        stock = branches;
        recipes = mockStatic(RecipeIndex.class, withSettings().stubOnly());
        recipes.when(() -> RecipeIndex.get(level)).thenReturn(index);
        recipes.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
    }

    public static DeepPlanningFixture fullTree(int levels, int trunkDepth, int routeCount,
                                               int rootAlternatives, Consumer<ModType> planningCallback) {
        return new DeepPlanningFixture(levels, trunkDepth,
                new NativeRoutes(routeCount, planningCallback), rootAlternatives);
    }

    private DeepPlanningFixture(int levels, int trunkDepth, NativeRoutes routes, int rootAlternatives) {
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(level.getRecipeManager()).thenReturn(routes.manager);
        nativeRoutes = routes;
        int nodes = (1 << levels) - 1;
        var items = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR && item != Items.STICK)
                .limit((long) nodes + trunkDepth).toList();
        Map<MaterialRef, List<RecipeNode>> outputs = new HashMap<>();
        Item target = items.get(0);
        for (int i = 0; i < trunkDepth; i++) {
            Item output = items.get(nodes + i);
            addNativeRecipe(routes, outputs, output, List.of(target), nodes + i, 1);
            target = output;
        }
        for (int i = nodes - 1; i >= 0; i--) {
            Item output = items.get(i);
            int child = 2 * i + 1;
            List<Item> inputs = child < nodes ? List.of(items.get(child), items.get(child + 1)) : List.of(Items.STICK);
            addNativeRecipe(routes, outputs, output, inputs, i, i == 0 ? rootAlternatives : 1);
        }
        firstRoot = target;
        demands = List.of(ScaledIngredientSpec.of(new IngredientSpec(Ingredient.of(firstRoot), 1), 1));
        pureDemands = List.of(need(firstRoot));
        graph = new ImmutableRecipeGraph(outputs);
        stock = 1L << (levels - 1);
        recipes = mockStatic(RecipeIndex.class, withSettings().stubOnly());
        recipes.when(() -> RecipeIndex.get(level)).thenReturn(index);
        recipes.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
        routes.open();
    }

    private void addNativeRecipe(NativeRoutes routes, Map<MaterialRef, List<RecipeNode>> outputs,
                                 Item output, List<Item> inputs, int node, int alternatives) {
        var entries = new ArrayList<RecipeIndex.Entry>();
        var projected = new ArrayList<RecipeNode>();
        for (int alternative = 0; alternative < alternatives; alternative++) {
            var id = new ResourceLocation("wide_tree", "node_" + node + "_route_" + alternative);
            ModType route = routes.types.get((node + alternative) % routes.types.size());
            if (inputs.size() > 1 && route == routes.stamper) route = routes.types.get(1);
            Recipe<?> recipe = routes.create(id, route, output, inputs);
            entries.add(new RecipeIndex.Entry(recipe, route, new ResourceLocation("test", route.id()), false));
            projected.add(new RecipeNode(id, material(output), 1, inputs.stream().map(DeepPlanningFixture::need).toList()));
        }
        index.put(output, List.copyOf(entries));
        outputs.put(material(output), List.copyOf(projected));
    }

    private static final class NativeRoutes implements AutoCloseable {
        private static final ThreadLocal<int[]> PROBE_CALLS = new ThreadLocal<>();
        private final ServerPlayer player = mock(ServerPlayer.class, withSettings().stubOnly());
        private final ServerLevel estimateLevel = mock(ServerLevel.class, withSettings().stubOnly());
        private final MinecraftServer server = mock(MinecraftServer.class, withSettings().stubOnly());
        private final RecipeManager manager = mock(RecipeManager.class, withSettings().stubOnly());
        private final Map<ResourceLocation, Recipe<?>> byId = new HashMap<>();
        private final Map<Recipe<?>, ModRecipeHandlers.Claim> claims = new IdentityHashMap<>();
        private final List<ModType> types = new ArrayList<>();
        private final Consumer<ModType> callback;
        private final ModType stamper;
        private MockedStatic<ModRecipeHandlers> handlers;
        private MockedStatic<AltarBindingRegistry> bindings;

        NativeRoutes(int routeCount, Consumer<ModType> callback) {
            this.callback = callback;
            ModType existing = ModType.findById(ModIds.ID_EMBERS_STAMPER);
            stamper = existing == null
                    ? ModType.register(ModIds.ID_EMBERS_STAMPER, new String[0], new String[0],
                            new String[0], () -> {
                                recordProbe();
                                return new EmbersStamperBatchDelegate();
                            })
                    : existing;
            types.add(stamper);
            for (int i = 1; i < routeCount; i++) {
                String id = "wide_fixture_" + i;
                ModType type = ModType.findById(id);
                types.add(type == null ? register(id) : type);
            }
        }

        private static ModType register(String id) {
            return ModType.register(id, new String[0], new String[0], new String[0], () -> {
                recordProbe();
                return new GenericBatchDelegate();
            });
        }

        private static void recordProbe() {
            int[] count = PROBE_CALLS.get();
            if (count != null) count[0]++;
        }

        Recipe<?> create(ResourceLocation id, ModType type, Item output, List<Item> inputs) {
            if (type == stamper) {
                // 余烬使用真实 handler 和 delegate，世界、绑定和配方实例仍为测试替身。
                var stamping = mock(IStampingRecipe.class, withSettings().stubOnly());
                var noFluid = mock(FluidIngredient.class, withSettings().stubOnly());
                when(noFluid.getFluids()).thenReturn(List.of());
                when(stamping.getDisplayInputFluid()).thenReturn(noFluid);
                when(stamping.getDisplayInput()).thenReturn(Ingredient.of(inputs.get(0)));
                when(stamping.getDisplayStamp()).thenReturn(Ingredient.EMPTY);
                when(stamping.getResultItem()).thenAnswer(call -> {
                    callback.accept(type);
                    return new ItemStack(output);
                });
                return saveRecipe(id, stamping, new EmbersStampingRecipeHandler());
            }
            Recipe<?> recipe = mock(Recipe.class, withSettings().stubOnly());
            List<IngredientSpec> specs = inputs.stream()
                    .map(item -> new IngredientSpec(Ingredient.of(item), 1)).toList();
            ModRecipeHandler handler = new ModRecipeHandler() {
                @Override public ModType modType() { return type; }
                @Override public boolean canHandle(Recipe<?> candidate) { return candidate == recipe; }
                @Override public boolean preferHandlerIngredients() { return true; }
                @Override public ItemStack getResultItem(Recipe<?> candidate, RegistryAccess access) {
                    callback.accept(type);
                    return new ItemStack(output);
                }
                @Override public List<IngredientSpec> getIngredients(Recipe<?> candidate) {
                    callback.accept(type);
                    return specs;
                }
                @Override public boolean isAvailableForPlanning(Recipe<?> candidate, ServerPlayer context) {
                    callback.accept(type);
                    return true;
                }
            };
            return saveRecipe(id, recipe, handler);
        }

        private Recipe<?> saveRecipe(ResourceLocation id, Recipe<?> recipe, ModRecipeHandler handler) {
            when(recipe.getId()).thenReturn(id);
            byId.put(id, recipe);
            claims.put(recipe, new ModRecipeHandlers.Claim(handler, List.of(handler)));
            return recipe;
        }

        void open() {
            when(player.getServer()).thenReturn(server);
            when(player.serverLevel()).thenReturn(estimateLevel);
            when(server.getLevel(any())).thenReturn(estimateLevel);
            when(estimateLevel.isLoaded(any())).thenReturn(true);
            when(estimateLevel.getRecipeManager()).thenReturn(manager);
            when(manager.byKey(any())).thenAnswer(call -> Optional.ofNullable(byId.get(call.getArgument(0))));
            handlers = mockStatic(ModRecipeHandlers.class, withSettings().stubOnly().defaultAnswer(call -> {
                if (call.getMethod().getName().equals("claimFor")) {
                    var claim = claims.get(call.getArgument(0));
                    if (claim != null) return claim;
                }
                return call.callRealMethod();
            }));
            bindings = mockStatic(AltarBindingRegistry.class, withSettings().stubOnly());
            bindings.when(() -> AltarBindingRegistry.hasBindingForRecipe(eq(player), any(), any()))
                    .thenReturn(true);
            bindings.when(() -> AltarBindingRegistry.getBoundMachinesForRecipe(eq(player), any(), any()))
                    .thenAnswer(call -> {
                        ModType type = call.getArgument(1);
                        return List.of(new AltarBindingRegistry.BoundMachine(
                                new ResourceLocation("minecraft", "overworld"), BlockPos.ZERO, type, type.id()));
                    });
        }

        @Override
        public void close() {
            PROBE_CALLS.remove();
            bindings.close();
            handlers.close();
        }
    }

    public ItemStack target() {
        return new ItemStack(firstRoot);
    }

    public ParallelPlanEstimator.Request parallelEstimator() {
        NativeRoutes.PROBE_CALLS.set(new int[1]);
        return new ParallelPlanEstimator.Request(nativeRoutes.player, Map.of(Items.STICK, stock));
    }

    public int probeCalls() {
        return NativeRoutes.PROBE_CALLS.get()[0];
    }

    private Item addRecipe(Map<MaterialRef, List<RecipeNode>> outputs, Item output, Item input, boolean mixed) {
        ResourceLocation id = new ResourceLocation("test", "deep_" + index.size());
        ResourceLocation type = new ResourceLocation("minecraft", "crafting");
        var recipe = new ShapelessRecipe(id, "", CraftingBookCategory.MISC, new ItemStack(output),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(input)));
        ModType mod = mixed && index.size() % 3 == 0 ? ModType.FLUID_CONTAINER : ModType.GENERIC;
        index.put(output, List.of(new RecipeIndex.Entry(recipe, mod, type, false)));
        outputs.put(material(output), List.of(new RecipeNode(id, material(output), 1, List.of(need(input)))));
        return output;
    }

    public CraftPlanGraph typed(boolean withStock, int timeoutMs, List<String> missing) {
        return typed(withStock, false, timeoutMs, missing);
    }

    public CraftPlanGraph typed(boolean withStock, boolean machineSlots, int timeoutMs, List<String> missing) {
        return CraftingResolver.resolveTypedPreviewGraph(demands, available(withStock), level,
                null, null, missing, null, machineSlots, timeoutMs, null, Map.of(), PlanningPreferences.empty());
    }

    public CraftingResolver.TypedPreview sliced(boolean withStock, int timeoutMs, List<String> missing) {
        return CraftingResolver.startTypedPreview(demands, available(withStock), level,
                nativeRoutes == null ? null : nativeRoutes.player, null,
                missing, null, true, timeoutMs, null, Map.of(), PlanningPreferences.empty());
    }

    CraftingResolver.TypedPreview supplemental(Set<ResourceLocation> excluded, List<String> missing) {
        var slots = demands.stream().map(s -> new IngredientSpec(s.ingredient(), Math.toIntExact(s.count()),
                s.consumption())).toList();
        return CraftingResolver.startSupplementalGraph(slots, available(true), level, null, null, missing,
                excluded, Map.of(), Map.of(), null);
    }

    public CraftPlanGraph legacyTyped(int timeoutMs, List<String> missing) {
        return CraftingResolver.resolveGraphForScaledSpecsWithTypes(demands, available(true), level,
                null, null, missing, null, true, timeoutMs, null, Map.of());
    }

    private Map<StackKey, Long> available(boolean withStock) {
        return withStock ? Map.of(new StackKey(Items.STICK, null), stock) : Map.of();
    }

    public PureRecipePlanner.Result pure(boolean withStock) {
        return PureRecipePlanner.resolve(graph, withStock ? Map.of(material(Items.STICK), stock) : Map.of(),
                pureDemands, RSIntegrationConfig.CRAFTING_MAX_STEPS.get());
    }

    private static IngredientRef need(Item item) {
        return new IngredientRef(List.of(material(item)), 1);
    }

    private static MaterialRef material(Item item) {
        return new MaterialRef(BuiltInRegistries.ITEM.getKey(item), "");
    }

    @Override
    public void close() {
        if (nativeRoutes != null) nativeRoutes.close();
        recipes.close();
    }
}
