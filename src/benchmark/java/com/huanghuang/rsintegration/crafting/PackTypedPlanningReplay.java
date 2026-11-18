package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.CompoundIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** 固定配方由替身 handler 提供；库存分配、候选选择和分片使用生产代码。 */
public final class PackTypedPlanningReplay extends BootstrapTest {
    private static boolean initialized;
    private final Map<MaterialRef, ItemStack> aliases = new HashMap<>();
    private final Map<ResourceLocation, List<MaterialRef>> aliasesById = new HashMap<>();
    private final Map<Item, List<RecipeIndex.Entry>> index = new HashMap<>();
    private final Map<ResourceLocation, ModType> routes = new HashMap<>();
    private final Map<ResourceLocation, Counts> counts = new HashMap<>();
    private final Map<ResourceLocation, ResourceLocation> realItems = new HashMap<>();
    private int latestCallbacks;

    public static void run(String label, ImmutableRecipeGraph graph, RecipeNode target,
                           Map<MaterialRef, Long> stock, Map<ResourceLocation, ResourceLocation> forced) {
        if (!initialized) {
            bootstrapMinecraft();
            DeepPlanningFixture.initializeMod();
            var config = CommentedConfig.inMemory();
            RSIntegrationConfig.SERVER_SPEC.correct(config);
            RSIntegrationConfig.SERVER_SPEC.setConfig(config);
            initialized = true;
        }
        new PackTypedPlanningReplay().solve(label, graph, target, stock, forced);
    }

    private void solve(String label, ImmutableRecipeGraph graph, RecipeNode target,
                       Map<MaterialRef, Long> stock, Map<ResourceLocation, ResourceLocation> forced) {
        Set<MaterialRef> known = new HashSet<>();
        for (RecipeNode recipe : graph.recipesById().values()) {
            known.addAll(recipe.guaranteedOutputs().keySet());
            recipe.inputs().forEach(input -> known.addAll(input.alternatives()));
        }
        Set<ResourceLocation> itemIds = new HashSet<>();
        known.forEach(material -> itemIds.add(material.itemId()));
        stock.keySet().stream().filter(material -> itemIds.contains(material.itemId())).forEach(known::add);
        List<Item> pool = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR).toList();
        int ordinal = 0;
        for (var id : known.stream().map(MaterialRef::itemId).distinct().sorted().toList()) {
            Item item = pool.get(ordinal % pool.size());
            ResourceLocation alias = BuiltInRegistries.ITEM.getKey(item);
            realItems.put(alias, id);
            int group = ordinal++ / pool.size();
            var variants = known.stream().filter(material -> material.itemId().equals(id))
                    .sorted(Comparator.comparing(MaterialRef::nbt)).toList();
            for (int i = 0; i < variants.size(); i++) {
                MaterialRef material = variants.get(i);
                ItemStack stack = new ItemStack(item);
                // 标签复用原版物品注册表，但不会让不同模组材料共享库存。
                stack.getOrCreateTag().putString("rsi_replay", group + ":" + i);
                aliases.put(material, stack);
            }
            aliasesById.put(id, variants);
        }

        Map<Recipe<?>, ModRecipeHandlers.Claim> claims = new HashMap<>();
        Map<ResourceLocation, Recipe<?>> byId = new HashMap<>();
        graph.recipesById().values().stream().sorted(Comparator.comparing(RecipeNode::recipeId)).forEach(node -> {
            if (node == target) return;
            ModType route = routes.computeIfAbsent(node.recipeTypeId(), type -> {
                String id = "pack_replay_" + type.getNamespace() + '_' + type.getPath().replace('/', '_');
                ModType existing = ModType.findById(id);
                return existing != null ? existing : ModType.register(
                        id, new String[0], new String[0], new String[0], GenericBatchDelegate::new);
            });
            ItemStack output = aliases.get(node.output()).copyWithCount(Math.toIntExact(node.outputCount()));
            List<IngredientSpec> specs = node.inputs().stream().map(this::spec).toList();
            Recipe<?> recipe = new ReplayRecipe(node.recipeId(), output);
            byId.put(node.recipeId(), recipe);
            ModRecipeHandler handler = new ModRecipeHandler() {
                @Override public ModType modType() { return route; }
                @Override public boolean canHandle(Recipe<?> candidate) { return candidate == recipe; }
                @Override public boolean preferHandlerIngredients() { return true; }
                @Override public ItemStack getResultItem(Recipe<?> candidate, RegistryAccess access) {
                    record(node.recipeId()).outputs++;
                    return output;
                }
                @Override public List<IngredientSpec> getIngredients(Recipe<?> candidate) {
                    record(node.recipeId()).inputs++;
                    return specs;
                }
            };
            claims.put(recipe, new ModRecipeHandlers.Claim(handler, List.of(handler)));
            index.computeIfAbsent(output.getItem(), unused -> new ArrayList<>())
                    .add(new RecipeIndex.Entry(recipe, route, node.recipeTypeId(), true));
        });
        Level level = mock(Level.class, withSettings().stubOnly());
        ServerPlayer player = mock(ServerPlayer.class, withSettings().stubOnly());
        RecipeManager manager = mock(RecipeManager.class, withSettings().stubOnly());
        when(manager.byKey(any())).thenAnswer(call -> Optional.ofNullable(byId.get(call.getArgument(0))));
        when(level.getRecipeManager()).thenReturn(manager);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        Map<CraftingResolver.StackKey, Long> available = new LinkedHashMap<>();
        stock.forEach((material, amount) -> {
            ItemStack item = aliases.get(material);
            if (item != null) available.put(CraftingResolver.StackKey.of(item, true), amount);
        });
        Map<ResourceLocation, ResourceLocation> overrides = new HashMap<>();
        forced.forEach((material, recipe) -> {
            var variants = aliasesById.get(material);
            if (variants != null) {
                var alias = BuiltInRegistries.ITEM.getKey(aliases.get(variants.get(0)).getItem());
                if (material.equals(realItems.get(alias))) overrides.put(alias, recipe);
            }
        });
        var needed = target.inputs().stream().map(input -> ScaledIngredientSpec.of(spec(input), input.count())).toList();
        try (var recipes = mockStatic(RecipeIndex.class, withSettings().stubOnly());
             var handlers = mockStatic(ModRecipeHandlers.class, withSettings().stubOnly().defaultAnswer(call -> {
                 if (call.getMethod().getName().equals("claimFor")) {
                     var claim = claims.get(call.getArgument(0));
                     if (claim != null) return claim;
                 }
                 return call.callRealMethod();
             }));
             var bindings = mockStatic(AltarBindingRegistry.class, withSettings().stubOnly())) {
            recipes.when(() -> RecipeIndex.get(level)).thenReturn(index);
            recipes.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
            bindings.when(() -> AltarBindingRegistry.hasBindingForRecipe(eq(player), any(), any()))
                    .thenAnswer(call -> {
                        record(call.<Recipe<?>>getArgument(1).getId()).bindings++;
                        return true;
                    });
            for (int run = 0; run < 3; run++) measure(label, run, level, player, available, overrides, needed, target);
        }
    }

    private Counts record(ResourceLocation id) {
        latestCallbacks++;
        return counts.computeIfAbsent(id, ignored -> new Counts());
    }

    private void measure(String label, int run, Level level, ServerPlayer player,
                         Map<CraftingResolver.StackKey, Long> stock, Map<ResourceLocation, ResourceLocation> forced,
                         List<ScaledIngredientSpec> needed, RecipeNode target) {
        counts.clear();
        latestCallbacks = 0;
        List<String> missing = new ArrayList<>();
        var job = CraftingResolver.startTypedPreview(needed, stock, level, player, null, missing, forced,
                true, 4_000, new CraftingResolver.ActiveRootRecipe(target.recipeId(), aliases.get(target.output())),
                Map.of(), PlanningPreferences.empty());
        int slices = 0;
        int worstCallbacks = 0;
        long longest = 0L;
        long active = 0L;
        long graphTime = 0L;
        String status;
        int nodes = 0;
        int rootsMissing = 0;
        try {
            boolean done;
            do {
                int before = latestCallbacks;
                long start = System.nanoTime();
                done = job.advance(5_000_000L);
                long elapsed = System.nanoTime() - start;
                longest = Math.max(longest, elapsed);
                active += elapsed;
                worstCallbacks = Math.max(worstCallbacks, latestCallbacks - before);
                slices++;
            } while (!done);
            long start = System.nanoTime();
            var result = job.graph();
            graphTime = System.nanoTime() - start;
            nodes = result.nodes().size();
            rootsMissing = (int) result.rootDemands().stream().filter(root -> root.unresolvedQuantity() > 0).count();
            status = rootsMissing == 0 && result.unresolvedDemands().isEmpty() ? "SUCCESS" : "PARTIAL";
        } catch (CraftingPlanningTimeoutException timeout) {
            status = "TIMEOUT";
        } catch (CraftingPlanningLimitException limit) {
            status = "LIMIT";
        }
        System.out.printf("typed_%s run=%d status=%s nodes=%d unresolvedRoots=%d slices=%d activeMs=%.1f maxSliceMs=%.1f graphMs=%.1f "
                        + "inputs=%d outputs=%d bindings=%d maxCallbacks=%d hottest=%s%n",
                label, run, status, nodes, rootsMissing, slices, active / 1e6, longest / 1e6, graphTime / 1e6,
                counts.values().stream().mapToInt(c -> c.inputs).sum(),
                counts.values().stream().mapToInt(c -> c.outputs).sum(),
                counts.values().stream().mapToInt(c -> c.bindings).sum(), worstCallbacks,
                counts.entrySet().stream().sorted(Map.Entry.<ResourceLocation, Counts>comparingByValue(
                                Comparator.comparingInt(Counts::total)).reversed())
                        .limit(6).map(entry -> entry.getKey() + ":" + entry.getValue().total()).toList());
    }

    private IngredientSpec spec(IngredientRef input) {
        Ingredient[] variants = input.alternatives().stream()
                .flatMap(material -> input.nbtMatchMode() == NbtMatchMode.ANY
                        ? aliasesById.get(material.itemId()).stream() : Stream.of(material))
                .distinct().map(material -> StrictNBTIngredient.of(aliases.get(material)))
                .toArray(Ingredient[]::new);
        return new IngredientSpec(CompoundIngredient.of(variants), Math.toIntExact(input.count()),
                input.role() == DemandRole.CATALYST ? InputConsumption.catalyst() : InputConsumption.consumed());
    }

    private record ReplayRecipe(ResourceLocation id, ItemStack output) implements Recipe<Container> {
        @Override public ResourceLocation getId() { return id; }
        @Override public ItemStack getResultItem(RegistryAccess access) { return output; }
        @Override public boolean matches(Container inventory, Level level) { return false; }
        @Override public ItemStack assemble(Container inventory, RegistryAccess access) { return output.copy(); }
        @Override public boolean canCraftInDimensions(int width, int height) { return false; }
        @Override public RecipeSerializer<?> getSerializer() { return RecipeSerializer.SHAPELESS_RECIPE; }
        @Override public RecipeType<?> getType() { return RecipeType.CRAFTING; }
    }

    private static final class Counts {
        int inputs;
        int outputs;
        int bindings;
        int total() { return inputs + outputs + bindings; }
    }
}
