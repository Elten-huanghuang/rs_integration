package com.huanghuang.rsintegration.crafting;
import com.huanghuang.rsintegration.crafting.batch.VirtualExchangeRecipe;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftNodeMergePolicy;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraphCompactor;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
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
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.util.PerformanceMonitor;
import com.huanghuang.rsintegration.mods.farmersdelight.MinersDelightCopperPotSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRecipeHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.huanghuang.rsintegration.magic.MagicResourceCost;

public final class CraftingResolver {

    /** The terminal recipe currently consuming the roots being resolved. */
    public record ActiveRootRecipe(ResourceLocation recipeId, ItemStack output) {
        public ActiveRootRecipe {
            Objects.requireNonNull(recipeId, "recipeId");
            output = Objects.requireNonNull(output, "output").copy();
        }

        @Override
        public ItemStack output() {
            return output.copy();
        }
    }
    /** Stable preference key that preserves NBT identity while remaining wire-compatible. */
    public static ResourceLocation preferenceKey(ItemStack stack) {
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null || !stack.hasTag()) return itemId;
        String identity = itemId + "|" + stack.getTag();
        UUID hash = UUID.nameUUIDFromBytes(
                identity.getBytes(StandardCharsets.UTF_8));
        return new ResourceLocation("rs_integration", "stack/" + hash.toString().replace("-", ""));
    }

    public static boolean isStackPreferenceKey(ResourceLocation key) {
        return key != null && "rs_integration".equals(key.getNamespace())
                && key.getPath().startsWith("stack/");
    }

    /** Synthetic async step: let Enigmatic Legacy taint an Earth Heart in the
     * player's inventory, then return that exact instance to the chain. */
    public static final ResourceLocation TAINT_EARTH_HEART_STEP =
            new ResourceLocation(RSIntegrationMod.MOD_ID, "internal/taint_earth_heart");
    private static final ResourceLocation EARTH_HEART_ID =
            new ResourceLocation("enigmaticlegacy", "earth_heart");

    private static final int MAX_ENSURE_CALLS =
            RSIntegrationConfig.DEFAULT_CRAFTING_MAX_ENSURE_CALLS;

    /** Per-plan recursion cap from config, falling back to the default. */
    private static int maxEnsureCalls() {
        try {
            return RSIntegrationConfig.CRAFTING_MAX_ENSURE_CALLS.get();
        } catch (Exception e) {
            return MAX_ENSURE_CALLS;
        }
    }

    /** Cache for {@link #extractHiddenOutput(Recipe)} — avoids repeated
     *  reflection field-scanning in DFS candidate loops. */
    private static final ConcurrentHashMap<ResourceLocation, ItemStack>
            HIDDEN_OUTPUT_CACHE = new ConcurrentHashMap<>();

    private CraftingResolver() {}

    /** 配方重载后丢弃按配方 ID 缓存的隐藏产物。 */
    public static void clearHiddenOutputCache() {
        HIDDEN_OUTPUT_CACHE.clear();
    }

    public static List<ResourceLocation> resolveStepsForKeyed(
            List<ItemStack> needed,
            Map<StackKey, ? extends Number> available,
            Level level,
            @Nullable List<String> missingOut) {
        return resolveStepsForStacks(needed, stacksFromKeyedCounts(available), level, missingOut);
    }

    public static List<ResourceLocation> resolveStepsForStacks(
            List<ItemStack> needed,
            List<ItemStack> available,
            Level level,
            @Nullable List<String> missingOut) {
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level),
                available,
                buildPreferredRecipes(level));

        EdgeTracker edges = new EdgeTracker();
        for (ItemStack stack : needed) {
            if (stack.isEmpty()) continue;
            Ingredient ing = ingredientOf(stack, stack.hasTag());
            if (!ensureIngredient(ing, stack.getCount(), ctx, 0, edges)) {
                if (missingOut != null) {
                    missingOut.add(describeItem(stack));
                }
            }
        }
        return ctx.steps.stream().map(ResolutionStep::recipeId).collect(Collectors.toList());
    }

    public static List<ResourceLocation> resolveStepsForIngredients(
            List<Ingredient> needed,
            List<ItemStack> available,
            Level level,
            @Nullable List<String> missingOut) {
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level),
                available,
                buildPreferredRecipes(level));

        return resolveIngredientRoots(needed, ctx, missingOut).stream()
                .map(ResolutionStep::recipeId).collect(Collectors.toList());
    }

    public static List<ResourceLocation> resolveStepsForIngredients(
            List<Ingredient> needed,
            List<ItemStack> available,
            Level level,
            @Nullable List<String> missingOut,
            Map<ResourceLocation, ResourceLocation> forcedOverrides) {
        Map<ResourceLocation, ResourceLocation> prefs = mergeForcedOverrides(level, forcedOverrides);
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level),
                available,
                prefs,
                forcedOverrides,
                null,
                null);

        return resolveIngredientRoots(needed, ctx, missingOut).stream()
                .map(ResolutionStep::recipeId).collect(Collectors.toList());
    }

    public static List<ResolutionStep> resolveStepsForIngredientsWithTypes(
            List<Ingredient> needed,
            List<ItemStack> available,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut) {
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level),
                available,
                buildPreferredRecipes(level),
                player,
                storageEndpoint);

        return resolveIngredientRoots(needed, ctx, missingOut);
    }

    public static List<ResolutionStep> resolveStepsForIngredientsWithTypes(
            List<Ingredient> needed,
            Map<StackKey, ? extends Number> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut) {
        return resolveStepsForIngredientsWithTypes(needed, availableKeyed, level,
                player, storageEndpoint, missingOut, null);
    }

    public static List<ResolutionStep> resolveStepsForIngredientsWithTypes(
            List<Ingredient> needed,
            Map<StackKey, ? extends Number> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides) {
        return resolveStepsForIngredientsWithTypes(needed, availableKeyed, level,
                player, storageEndpoint, missingOut, forcedOverrides, false);
    }

    public static List<ResolutionStep> resolveStepsForIngredientsWithTypes(
            List<Ingredient> needed,
            Map<StackKey, ? extends Number> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort) {
        Map<ResourceLocation, ResourceLocation> prefs = mergeForcedOverrides(level, forcedOverrides);
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level),
                availableKeyed,
                prefs,
                forcedOverrides,
                player,
                storageEndpoint,
                bestEffort,
                missingOut);

        return resolveIngredientRoots(needed, ctx, missingOut);
    }

    private static List<ResolutionStep> resolveIngredientRoots(
            List<Ingredient> needed, ResolutionContext ctx,
            @Nullable List<String> missingOut) {
        EdgeTracker edges = new EdgeTracker();
        for (Ingredient ingredient : needed) {
            if (ingredient.isEmpty()) continue;
            if (!ensureIngredient(ingredient, 1, ctx, 0, edges)
                    && missingOut != null) {
                missingOut.add(describeFirstItem(ingredient));
            }
        }
        ctx.dumpDiagnostics();
        return new ArrayList<>(ctx.steps);
    }

    // ── IngredientSpec-based resolution (preserves Tag + real count) ──

    /**
     * Resolve crafting steps for ingredients with per-slot counts preserved.
     * Unlike the older {@code resolveStepsForIngredientsWithTypes} which
     * hardcodes count=1, this method uses the real {@code spec.count()} so
     * recipes requiring multiple items per slot (e.g. 5 glass panes) are
     * planned correctly.
     */
    public static List<ResolutionStep> resolveStepsForSpecsWithTypes(
            List<IngredientSpec> needed,
            Map<StackKey, ? extends Number> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort) {
        Map<ResourceLocation, ResourceLocation> prefs = mergeForcedOverrides(level, forcedOverrides);
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level),
                availableKeyed,
                prefs,
                forcedOverrides,
                player,
                storageEndpoint,
                bestEffort,
                missingOut);

        EdgeTracker edges = new EdgeTracker();
        List<IngredientSpec> rootsToResolve = new ArrayList<>(coalesceRootSpecs(needed));
        rootsToResolve.sort(StepExecutor.resolutionComparator());
        for (IngredientSpec spec : rootsToResolve) {
            if (spec.isEmpty()) continue;
            if (!ensureIngredient(spec.ingredient(), spec.count(), ctx, 0, edges)) {
                if (missingOut != null) {
                    missingOut.add(describeFirstItem(spec.ingredient()));
                }
            }
        }
        return new ArrayList<>(ctx.steps);
    }

    public static CraftPlanGraph resolveGraphForSpecsWithTypes(
            List<IngredientSpec> needed,
            Map<StackKey, ? extends Number> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort) {
        return resolveGraphForSpecsWithTypes(needed, availableKeyed, level, player, storageEndpoint,
                missingOut, forcedOverrides, bestEffort, false, -1, null, Map.of());
    }

    public static CraftPlanGraph resolveGraphForSpecsWithTypes(
            List<IngredientSpec> needed,
            Map<StackKey, ? extends Number> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort,
            int timeoutMs) {
        return resolveGraphForSpecsWithTypes(needed, availableKeyed, level, player, storageEndpoint,
                missingOut, forcedOverrides, bestEffort, false, timeoutMs, null, Map.of());
    }

    public static CraftPlanGraph resolveGraphForSpecsWithTypes(
            List<IngredientSpec> needed,
            Map<StackKey, ? extends Number> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort,
            int timeoutMs,
            ActiveRootRecipe activeRoot) {
        return resolveGraphForSpecsWithTypes(needed, availableKeyed, level, player, storageEndpoint,
                missingOut, forcedOverrides, bestEffort, false, timeoutMs, activeRoot, Map.of());
    }

    public static CraftPlanGraph resolveGraphForSpecsWithTypes(
            List<IngredientSpec> needed, Map<StackKey, ? extends Number> availableKeyed,
            Level level, @Nullable ServerPlayer player, @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort, int timeoutMs, @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks) {
        return resolveGraphForSpecsWithTypes(needed, availableKeyed, level, player, storageEndpoint,
                missingOut, forcedOverrides, bestEffort, false, timeoutMs, activeRoot, materialLocks);
    }

    public static CraftPlanGraph resolveGraphForSpecsWithTypes(
            List<IngredientSpec> needed, Map<StackKey, ? extends Number> availableKeyed,
            Level level, @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort, int timeoutMs, @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks, PlanningPreferences preferences) {
        return resolveGraphForSpecsWithTypes(needed, availableKeyed, level, player,
                storageEndpoint, missingOut, forcedOverrides, bestEffort, false, timeoutMs,
                activeRoot, materialLocks, preferences.excludedRecipes(), preferences);
    }

    public static CraftPlanGraph resolveGraphForScaledSpecsWithTypes(
            List<ScaledIngredientSpec> needed, Map<StackKey, ? extends Number> availableKeyed,
            Level level, @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort, int timeoutMs, @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks, PlanningPreferences preferences) {
        List<RootSpec> roots = rootSpecs(needed);
        return resolveGraphForRootSpecs(roots, availableKeyed, level, player,
                storageEndpoint, missingOut, forcedOverrides, bestEffort, false,
                timeoutMs, activeRoot, materialLocks,
                preferences.excludedRecipes(), preferences);
    }

    public static CraftPlanGraph resolveGraphForScaledSpecsWithTypes(
            List<ScaledIngredientSpec> needed, Map<StackKey, ? extends Number> availableKeyed,
            Level level, @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort, int timeoutMs, @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks) {
        return resolveGraphForScaledSpecsWithTypes(needed, availableKeyed, level, player,
                storageEndpoint, missingOut, forcedOverrides, bestEffort, timeoutMs,
                activeRoot, materialLocks, PlanningPreferences.empty());
    }

    public static CraftPlanGraph resolveMachineGraphForScaledSpecsWithTypes(
            List<ScaledIngredientSpec> needed, Map<StackKey, ? extends Number> availableKeyed,
            Level level, @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort, int timeoutMs, @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks, PlanningPreferences preferences) {
        List<RootSpec> roots = rootSpecs(needed);
        return resolveGraphForRootSpecs(roots, availableKeyed, level, player,
                storageEndpoint, missingOut, forcedOverrides, bestEffort, true,
                timeoutMs, activeRoot, materialLocks,
                preferences.excludedRecipes(), preferences);
    }

    public static CraftPlanGraph resolveMachineGraphForScaledSpecsWithTypes(
            List<ScaledIngredientSpec> needed, Map<StackKey, ? extends Number> availableKeyed,
            Level level, @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort, int timeoutMs, @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks) {
        return resolveMachineGraphForScaledSpecsWithTypes(needed, availableKeyed, level, player,
                storageEndpoint, missingOut, forcedOverrides, bestEffort, timeoutMs,
                activeRoot, materialLocks, PlanningPreferences.empty());
    }

    /**
     * Resolve the root inputs of one machine recipe. Each {@link IngredientSpec}
     * represents one physical machine slot, so a tag demand must be backed by one
     * concrete stack variant for its complete quantity. For example, a two-wool
     * altar slot may use two white wool, but never one white plus one red wool.
     */
    public static CraftPlanGraph resolveMachineGraphForSpecsWithTypes(
            List<IngredientSpec> needed,
            Map<StackKey, ? extends Number> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort) {
        return resolveGraphForSpecsWithTypes(needed, availableKeyed, level, player, storageEndpoint,
                missingOut, forcedOverrides, bestEffort, true, -1, null, Map.of());
    }

    public static CraftPlanGraph resolveMachineGraphForSpecsWithTypes(
            List<IngredientSpec> needed,
            Map<StackKey, ? extends Number> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort,
            int timeoutMs) {
        return resolveGraphForSpecsWithTypes(needed, availableKeyed, level, player, storageEndpoint,
                missingOut, forcedOverrides, bestEffort, true, timeoutMs, null, Map.of());
    }

    public static CraftPlanGraph resolveMachineGraphForSpecsWithTypes(
            List<IngredientSpec> needed,
            Map<StackKey, ? extends Number> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort,
            int timeoutMs,
            ActiveRootRecipe activeRoot) {
        return resolveGraphForSpecsWithTypes(needed, availableKeyed, level, player, storageEndpoint,
                missingOut, forcedOverrides, bestEffort, true, timeoutMs, activeRoot, Map.of());
    }

    public static CraftPlanGraph resolveMachineGraphForSpecsWithTypes(
            List<IngredientSpec> needed, Map<StackKey, ? extends Number> availableKeyed,
            Level level, @Nullable ServerPlayer player, @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort, int timeoutMs, @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks) {
        return resolveGraphForSpecsWithTypes(needed, availableKeyed, level, player, storageEndpoint,
                missingOut, forcedOverrides, bestEffort, true, timeoutMs, activeRoot, materialLocks);
    }

    public static CraftPlanGraph resolveMachineGraphForSpecsWithTypes(
            List<IngredientSpec> needed, Map<StackKey, ? extends Number> availableKeyed,
            Level level, @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort, int timeoutMs, @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks, PlanningPreferences preferences) {
        return resolveGraphForSpecsWithTypes(needed, availableKeyed, level, player,
                storageEndpoint, missingOut, forcedOverrides, bestEffort, true, timeoutMs,
                activeRoot, materialLocks, preferences.excludedRecipes(), preferences);
    }

    private static CraftPlanGraph resolveGraphForSpecsWithTypes(
            List<IngredientSpec> needed,
            Map<StackKey, ? extends Number> availableKeyed,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean bestEffort,
            boolean singleVariantRoots,
            int timeoutMs,
            @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks) {
        return resolveGraphForSpecsWithTypes(needed, availableKeyed, level, player, storageEndpoint,
                missingOut, forcedOverrides, bestEffort, singleVariantRoots, timeoutMs,
                activeRoot, materialLocks, Set.of());
    }

    /** 补做原料沿用同一规划器，并排除当前等待中的生产步骤以阻断跨轮次循环。 */
    static CraftPlanGraph resolveSupplementalGraph(List<IngredientSpec> needed,
            Map<StackKey, ? extends Number> available, Level level, ServerPlayer player, CraftStorageEndpoint storageEndpoint,
            List<String> missing, Set<ResourceLocation> excludedRecipes,
            Map<ResourceLocation, ResourceLocation> forcedRecipes, Map<String, ItemStack> materialLocks,
            ActiveRootRecipe activeRoot) {
        return resolveGraphForSpecsWithTypes(needed, available, level, player, storageEndpoint, missing,
                forcedRecipes, false, true, 50, activeRoot, materialLocks, excludedRecipes);
    }

    private static CraftPlanGraph resolveGraphForSpecsWithTypes(
            List<IngredientSpec> needed, Map<StackKey, ? extends Number> availableKeyed, Level level,
            @Nullable ServerPlayer player, @Nullable CraftStorageEndpoint storageEndpoint, @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides, boolean bestEffort,
            boolean singleVariantRoots, int timeoutMs, @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks, Set<ResourceLocation> excludedRecipes) {
        return resolveGraphForSpecsWithTypes(needed, availableKeyed, level, player,
                storageEndpoint, missingOut, forcedOverrides, bestEffort, singleVariantRoots,
                timeoutMs, activeRoot, materialLocks, excludedRecipes,
                PlanningPreferences.empty());
    }

    private static CraftPlanGraph resolveGraphForSpecsWithTypes(
            List<IngredientSpec> needed, Map<StackKey, ? extends Number> availableKeyed, Level level,
            @Nullable ServerPlayer player, @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides, boolean bestEffort,
            boolean singleVariantRoots, int timeoutMs, @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks, Set<ResourceLocation> excludedRecipes,
            PlanningPreferences preferences) {
        List<RootSpec> roots = ingredientRootSpecs(needed);
        return resolveGraphForRootSpecs(roots, availableKeyed, level, player,
                storageEndpoint, missingOut, forcedOverrides, bestEffort,
                singleVariantRoots, timeoutMs, activeRoot, materialLocks,
                excludedRecipes, preferences);
    }

    private static List<RootSpec> rootSpecs(List<ScaledIngredientSpec> needed) {
        List<RootSpec> roots = new ArrayList<>(needed.size());
        for (int index = 0; index < needed.size(); index++) {
            ScaledIngredientSpec spec = needed.get(index);
            if (spec == null || spec.isEmpty()) continue;
            roots.add(new RootSpec(
                    spec.ingredient(), spec.count(), spec.consumption(), index));
        }
        return List.copyOf(roots);
    }

    private static List<RootSpec> ingredientRootSpecs(List<IngredientSpec> needed) {
        List<RootSpec> roots = new ArrayList<>(needed.size());
        for (int index = 0; index < needed.size(); index++) {
            IngredientSpec spec = needed.get(index);
            if (spec == null || spec.isEmpty()) continue;
            roots.add(new RootSpec(
                    spec.ingredient(), spec.count(), spec.consumption(), index));
        }
        return List.copyOf(roots);
    }

    private static CraftPlanGraph resolveGraphForRootSpecs(
            List<RootSpec> needed, Map<StackKey, ? extends Number> availableKeyed, Level level,
            @Nullable ServerPlayer player, @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides, boolean bestEffort,
            boolean singleVariantRoots, int timeoutMs, @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks, Set<ResourceLocation> excludedRecipes,
            PlanningPreferences preferences) {
        Map<ResourceLocation, ResourceLocation> prefs = mergeForcedOverrides(level, forcedOverrides);
        Map<Item, List<RecipeIndex.Entry>> index = RecipeIndex.get(level);
        ResolutionContext ctx = timeoutMs > 0
                ? new ResolutionContext(level, index, availableKeyed,
                        prefs, forcedOverrides, player, storageEndpoint, bestEffort, missingOut,
                        ResolutionContext.deadlineAfterMillis(timeoutMs),
                        shouldAbortOnTimeout(bestEffort))
                : new ResolutionContext(level, index, availableKeyed,
                        prefs, forcedOverrides, player, storageEndpoint, bestEffort, missingOut);
        ctx.excludedRecipes = Set.copyOf(excludedRecipes);
        ctx.withMaterialLocks(materialLocks);
        ctx.withPreferences(preferences);
        EdgeTracker edges = new EdgeTracker();
        Map<Integer, RootDemand> rootsByOrder = new TreeMap<>();

        boolean rootActive = activeRoot != null && !activeRoot.output().isEmpty();
        if (rootActive) {
            ctx.recipeCycles.enter(activeRoot.recipeId(), activeRoot.output());
        }

        try {
            // Crafting-grid roots are fungible planning demand and may be coalesced.
            // Machine roots are physical input slots and must retain their boundaries.
            List<RootSpec> rootsToResolve = singleVariantRoots
                    ? machineRootDemands(needed)
                    : coalesceRootDemands(needed);
            if (!singleVariantRoots) {
                // Resolve reusable catalysts after ordinary inputs. This lets an
                // intermediate CraftTweaker recipe return a shared catalyst before
                // the terminal root reserves it.
                rootsToResolve = new ArrayList<>(rootsToResolve);
                rootsToResolve.sort(rootSpecComparator());
            }
            for (int rootIndex = 0; rootIndex < rootsToResolve.size(); rootIndex++) {
                RootSpec spec = rootsToResolve.get(rootIndex);
                if (spec.isEmpty()) continue;
                if (activeRoot != null) {
                    spec = new RootSpec(ctx.lockedIngredient(
                            activeRoot.recipeId(), spec.ingredient()),
                            spec.count(), spec.consumption(), spec.order());
                }
                List<ResolutionContext.SupplySlice> consumed = new ArrayList<>();
                Ingredient resolvedIngredient = spec.ingredient();
                int rootMissingCheckpoint = ctx.missingCount();
                boolean resolved;
                if (singleVariantRoots) {
                    resolvedIngredient = StepExecutor.ensureSingleVariantMachineInput(
                            spec.ingredient(), spec.count(), ctx, 0, edges, null, consumed);
                    resolved = resolvedIngredient != null;
                    if (!resolved) resolvedIngredient = spec.ingredient();
                } else {
                    resolved = ensureIngredient(resolvedIngredient, spec.count(),
                            ctx, 0, edges, null, consumed);
                }
                List<RootAllocation> allocations = new ArrayList<>(consumed.size());
                for (ResolutionContext.SupplySlice slice : consumed) {
                    allocations.add(new RootAllocation(slice.source(), slice.material(), slice.quantity()));
                }
                long supplied = consumed.stream()
                        .mapToLong(ResolutionContext.SupplySlice::quantity)
                        .reduce(0L, SaturatingLongMath::add);
                long missing = Math.max(0L, spec.count() - supplied);
                if (!resolved && ctx.missingCount() == rootMissingCheckpoint) {
                    recordFailureMissing(ctx, resolvedIngredient, rootMissingCheckpoint);
                }
                rootsByOrder.put(spec.order(), new RootDemand(
                        resolvedIngredient, spec.count(), missing,
                        firstDisplayStack(resolvedIngredient), allocations, spec.role()));
                if (!resolved && !bestEffort) break;
            }
        } finally {
            if (rootActive) {
                ctx.recipeCycles.leave(activeRoot.recipeId(), activeRoot.output());
            }
        }
        List<RootDemand> roots = new ArrayList<>(rootsByOrder.values());

        CraftPlanGraph graph = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                ctx.graphNodes, ctx.graphAllocations, roots, ctx.graphUnresolved,
                ctx.graphNodes.stream().map(CraftNode::id).toList());
        CraftPlanValidator.validate(graph);
        graph = CraftPlanGraphCompactor.compact(graph,
                CraftNodeMergePolicy.forLevel(level));
        ctx.logGuardSummary();
        ctx.dumpDiagnostics();
        return graph;
    }

    private record RootSpec(Ingredient ingredient, long count,
                            InputConsumption consumption, int order) {
        private RootSpec {
            Objects.requireNonNull(ingredient, "ingredient");
            Objects.requireNonNull(consumption, "consumption");
        }

        private boolean isEmpty() {
            return ingredient.isEmpty() || count <= 0L;
        }

        private DemandRole role() {
            return consumption.demandRole();
        }
    }

    private record RootSpecKey(DemandRole role, String ingredientClass,
                               String serializedIngredient, int fallbackIndex) {}

    private static List<RootSpec> machineRootDemands(List<RootSpec> specs) {
        List<RootSpec> ordered = new ArrayList<>(
                specs.stream().filter(spec -> !spec.isEmpty()).toList());
        // 仅改变解析顺序；order 会把结果放回原始机器槽位。
        ordered.sort(rootSpecComparator());
        return List.copyOf(ordered);
    }

    /** Test seam: machine roots resolve by dependency complexity, not slot order. */
    static List<Integer> machineRootResolutionOrder(List<IngredientSpec> specs) {
        return machineRootDemands(ingredientRootSpecs(specs)).stream()
                .map(RootSpec::order)
                .toList();
    }

    private static List<RootSpec> coalesceRootDemands(List<RootSpec> specs) {
        Map<RootSpecKey, RootSpec> merged = new LinkedHashMap<>();
        int fallbackIndex = 0;
        for (RootSpec spec : specs) {
            if (spec.isEmpty()) continue;
            RootSpecKey key;
            try {
                key = new RootSpecKey(spec.role(), spec.ingredient().getClass().getName(),
                        spec.ingredient().toJson().toString(), -1);
            } catch (Exception ignored) {
                key = new RootSpecKey(
                        spec.role(), "", "", fallbackIndex++);
            }
            merged.compute(key, (ignored, existing) -> existing == null
                    ? spec : new RootSpec(existing.ingredient(),
                    SaturatingLongMath.add(existing.count(), spec.count()),
                    existing.consumption(), existing.order()));
        }
        return List.copyOf(merged.values());
    }

    private static Comparator<RootSpec> rootSpecComparator() {
        return Comparator
                .comparingInt((RootSpec spec) -> spec.role() == DemandRole.CATALYST ? 1 : 0)
                .thenComparingInt(spec -> {
                    try {
                        return spec.ingredient().getItems().length == 1 ? 0 : 1;
                    } catch (RuntimeException | LinkageError ignored) {
                        return 1;
                    }
                });
    }

    private static ItemStack firstDisplayStack(Ingredient ingredient) {
        for (ItemStack stack : ingredient.getItems()) {
            if (stack != null && !stack.isEmpty() && stack.getItem() != Items.AIR)
                return stack.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    /** Vanilla-only variant that returns flat recipe-id list. */
    public static List<ResourceLocation> resolveStepsForSpecs(
            List<IngredientSpec> needed,
            Map<StackKey, ? extends Number> availableKeyed,
            Level level,
            @Nullable List<String> missingOut) {
        List<ItemStack> availStacks = stacksFromKeyedCounts(availableKeyed);
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level),
                availStacks,
                buildPreferredRecipes(level)).genericCandidatesOnly();

        EdgeTracker edges = new EdgeTracker();
        List<IngredientSpec> rootsToResolve = new ArrayList<>(coalesceRootSpecs(needed));
        rootsToResolve.sort(StepExecutor.resolutionComparator());
        for (IngredientSpec spec : rootsToResolve) {
            if (spec.isEmpty()) continue;
            if (!ensureIngredient(spec.ingredient(), spec.count(), ctx, 0, edges)) {
                if (missingOut != null) {
                    missingOut.add(describeFirstItem(spec.ingredient()));
                }
            }
        }
        return ctx.steps.stream().map(ResolutionStep::recipeId).collect(Collectors.toList());
    }

    public static List<ResolutionStep> resolveStepsForKeyedWithTypes(
            List<ItemStack> needed,
            Map<StackKey, ? extends Number> available,
            Level level,
            @Nullable ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut) {
        return resolveStepsForIngredientsWithTypes(
                needed.stream().map(s -> ingredientOf(s, s.hasTag())).collect(Collectors.toList()),
                available,
                level, player, storageEndpoint, missingOut);
    }

    static boolean ensureIngredient(Ingredient ingredient, long count, ResolutionContext ctx, int depth,
                                     EdgeTracker edges) {
        return ensureIngredient(ingredient, count, ctx, depth, edges, null, null);
    }

    static boolean ensureIngredient(Ingredient ingredient, long count, ResolutionContext ctx, int depth,
                                     EdgeTracker edges, @Nullable InputPortId consumer,
                                     @Nullable List<ResolutionContext.SupplySlice> consumedOut) {
        if (count <= 0) return true;
        int missingCheckpoint = ctx.missingCount();

        int minReserve = RSIntegrationConfig.getProtectedReserve(ingredient);
        ResourceLocation forcedRecipeId = ctx.forcedRecipeFor(ingredient);
        boolean forceProduction = forcedRecipeId != null
                && !ctx.isForcedRecipeActive(forcedRecipeId);

        ctx.beginUndo();
        edges.beginUndo();

        long consumeStart = System.nanoTime();
        boolean mayConsumeDirect = minReserve <= 0
                || ctx.countMatching(ingredient) >= count + minReserve;
        if (!forceProduction && mayConsumeDirect) {
            ResolutionContext.SupplyConsumption direct = ctx.consumeMatchingDetailed(ingredient, count);
            if (direct.complete()) {
                recordSupplyConsumption(ctx, consumer, consumedOut, direct);
                ctx.commitUndo();
                edges.commitUndo();
                return true;
            }
        }
        ctx.rollback();
        edges.rollback();

        // Budget guards gate only the expensive recursive candidate search below.
        // An ingredient already present in inventory is consumed above, BEFORE these
        // checks run — otherwise a large plan that exhausts the 500ms deadline (or the
        // ensureCalls cap) would falsely mark a directly-available ingredient as
        // missing purely because of its position in resolution order (e.g. Malum
        // spirits resolve last, after costly base-material sub-crafts).
        if (ctx.timedOut()) {
            PerformanceMonitor.recordResolveTimeout();
            if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient guard TIMEOUT depth=" + depth + " count=" + count);
            if (recordBestEffortUnresolved(
                    ctx, consumer, ingredient, count, "timeout-before-candidates")) {
                return true;
            }
            recordFailureMissing(ctx, ingredient, missingCheckpoint);
            return false;
        }
        if (++ctx.ensureCalls > maxEnsureCalls()) {
            if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient guard ENSURE_CALLS calls=" + ctx.ensureCalls
                    + " max=" + maxEnsureCalls() + " depth=" + depth + " count=" + count);
            return false;
        }

        long alreadyHave = forceProduction ? 0L : ctx.countMatching(ingredient);
        long consumeMs = (System.nanoTime() - consumeStart) / 1_000_000;
        long remaining = count - alreadyHave;
        if (!forceProduction && remaining > 0 && isTaintedEarthHeartRequirement(ingredient)) {
            Ingredient plainHeart = Ingredient.of(ForgeRegistries.ITEMS.getValue(EARTH_HEART_ID));
            NodeId nodeId = ctx.allocateNodeId();
            InputPortId inputPort = new InputPortId(nodeId, 0);
            if (plainHeart.getItems().length > 0
                    && ensureIngredient(plainHeart, remaining, ctx, depth + 1, edges, inputPort, null)) {
                ItemStack plainTemplate = plainHeart.getItems()[0].copyWithCount(1);
                ItemStack taintedTemplate = ingredient.getItems().length > 0
                        ? ingredient.getItems()[0].copyWithCount(1) : ItemStack.EMPTY;
                if (taintedTemplate.isEmpty()) return false;
                long taintedRemaining = remaining;
                while (taintedRemaining > 0L) {
                    int executions = SaturatingLongMath.clampToInt(taintedRemaining);
                    ResolutionStep step = new ResolutionStep(TAINT_EARTH_HEART_STEP, ModType.GENERIC,
                            TAINT_EARTH_HEART_STEP, List.of(), List.of(), false, executions,
                            plainTemplate, taintedTemplate);
                    ctx.steps.add(step);

                    ItemStack tainted = taintedTemplate.copyWithCount(1);
                    OutputPortId outputPort = new OutputPortId(nodeId, 0);
                    ctx.addProduced(tainted, executions, new MaterialSource.ProducerOutput(outputPort));
                    ctx.addGraphNode(new CraftNode(nodeId, step.recipeId(), step.modType().id(),
                            step.recipeTypeId(), step.executions(), step.alternativeIds(),
                            step.alternativeModTypes(), step.inferMode(), step.syntheticInput(),
                            step.syntheticOutput(), List.of(new InputDemand(inputPort, plainHeart,
                            executions, DemandRole.TRANSFORMED, plainTemplate)),
                            List.of(OutputDeclaration.item(outputPort, MaterialKey.of(tainted),
                                    executions, OutputKind.SYNTHETIC)), step.magicCosts()));
                    taintedRemaining -= executions;
                }
                alreadyHave = ctx.countMatching(ingredient);
                remaining = count - alreadyHave;
            }
        }
        if (!forceProduction && minReserve > 0) {
            remaining += minReserve;
        }

        if (remaining <= 0) {
            ResolutionContext.SupplyConsumption existing = ctx.consumeMatchingDetailed(ingredient, count);
            if (existing.complete()) recordSupplyConsumption(ctx, consumer, consumedOut, existing);
            if (existing.complete()) ctx.truncateMissing(missingCheckpoint);
            return existing.complete();
        }
        if (ctx.isNoRecipe(ingredient)) {
            ctx.beginUndo();
            edges.beginUndo();
            ResolutionContext.SupplyConsumption stocked =
                    ctx.consumeMatchingDetailed(ingredient, count);
            recordSupplyConsumption(ctx, consumer, consumedOut, stocked);
            ctx.commitUndo();
            edges.commitUndo();
            if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient stop: no_recipe for "
                    + describeFirstItem(ingredient) + " missing="
                    + Math.max(0L, count - stocked.supplied()));
            if (recordBestEffortUnresolved(
                    ctx, consumer, ingredient, count, "no-recipe")) {
                return true;
            }
            recordFailureMissing(ctx, ingredient, missingCheckpoint);
            return false;
        }

        long candStart = System.nanoTime();
        // 调试日志开启时顺手记下被跳过的候选与原因：玩家报告「计划树里有的配方执行时不见了」，
        // 现有日志只能看到剩下几个候选，看不到是谁按什么规则被滤掉的。
        List<CandidateEngine.CandidateDiagnostic> candidateDiag =
                RSIntegrationMod.isDebugLoggingEnabled() ? new ArrayList<>() : null;
        List<RecipeIndex.Entry> candidates =
                CandidateEngine.findCandidates(ingredient, ctx, candidateDiag, remaining);
        if (forceProduction) {
            candidates = prioritizeForcedCandidate(candidates,
                    candidate -> candidate.recipe().getId(), forcedRecipeId);
        }
        long candMs = (System.nanoTime() - candStart) / 1_000_000;
        if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient candidates=" + candidates.size() + " remaining=" + remaining + " depth=" + depth);
        if (RSIntegrationMod.isDebugLoggingEnabled()) {
            RSIntegrationMod.debug("[RSI-ensure] countMatch/consume={}ms, findCandidates={}ms, {} candidates for {}",
                    consumeMs, candMs, candidates.size(), describeFirstItem(ingredient));
        }
        if (candidates.isEmpty()) {
            if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient FAILED: no candidates for " + describeFirstItem(ingredient));
            logSkippedCandidates("no-candidates", ingredient, candidateDiag);
            if (recordBestEffortUnresolved(ctx, consumer, ingredient, count, "no-candidates")) {
                return true;
            }
            recordFailureMissing(ctx, ingredient, missingCheckpoint);
            return false;
        }

        record AliveCandidate(RecipeIndex.Entry entry, ItemStack output, int netGain) {}
        List<AliveCandidate> alive = new ArrayList<>();
        long aliveStart = System.nanoTime();
        for (RecipeIndex.Entry candidate : candidates) {
            if (ctx.timedOut()) {
                long aliveMs = (System.nanoTime() - aliveStart) / 1_000_000;
                RSIntegrationMod.debug("[RSI-ensure] alive-building timed out after {}ms, keeping {}/{} candidates",
                        aliveMs, alive.size(), candidates.size());
                PerformanceMonitor.recordResolveTimeout();
                break;
            }
            ItemStack out = CandidateEngine.outputForDemand(candidate, ingredient, ctx);
            // If the result is bare (no NBT), scan fields for the real
            // NBT-carrying output — TACZ/Applied Armorer hide it there.
            out = resolveDeclaredOutput(candidate.recipe(), out);
            // resolveDeclaredOutput intentionally clears runtime-derived NBT
            // for smithing handlers.  Here the requested ingredient already
            // pins the exact base variant, so restore that variant before the
            // resolver records/consumes the produced output.  Without this,
            // strict CraftTweaker inputs such as soul_blade{Unbreakable:1}
            // are reported as missing even though the smithing route exists.
            out = CandidateEngine.inheritSmithingBaseTag(candidate.recipe(), out, ingredient, ctx);
            out = MinersDelightCopperPotSupport.adaptResult(candidate.modType(), out);
            if (out.isEmpty() || out.getCount() <= 0) {
                if (ctx.diagnosticsEnabled()) {
                    ctx.diag("ensureIngredient REJECT " + candidate.recipe().getId()
                            + ": empty output class=" + candidate.recipe().getClass().getName());
                }
                continue;
            }
            int ng = netGainPerBatch(candidate, out, ctx.level.registryAccess());
            if (ng <= 0) {
                if (ctx.diagnosticsEnabled()) {
                    ctx.diag("ensureIngredient REJECT " + candidate.recipe().getId()
                            + ": non-positive net gain output=" + out.getCount()
                            + " selfConsumed=" + selfConsumedPerBatch(
                            candidate, out, ctx.level.registryAccess()));
                }
                continue;
            }
            alive.add(new AliveCandidate(candidate, out, ng));
        }

        // Forced selection controls which candidate may execute, but the complete viable list
        // must remain available as UI alternatives so the player can switch back afterwards.
        List<AliveCandidate> selectableAlive = List.copyOf(alive);
        if (forceProduction) {
            alive = forcedExecutionCandidates(alive,
                    candidate -> candidate.entry().recipe().getId(), forcedRecipeId);
            if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient forced recipe=" + forcedRecipeId
                    + " candidates=" + alive.size()
                    + " alternatives=" + selectableAlive.size());
        }

        long aliveMs = (System.nanoTime() - aliveStart) / 1_000_000;
        if (alive.isEmpty()) {
            if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient FAILED: no viable candidates (total=" + candidates.size()
                    + ", all rejected) for " + describeFirstItem(ingredient));
            if (RSIntegrationMod.isDebugLoggingEnabled()) {
                RSIntegrationMod.debug("[RSI-ensure] no viable candidates for {} ({} total candidates rejected, alive-building took {}ms)",
                        describeFirstItem(ingredient), candidates.size(), aliveMs);
            }
            logSkippedCandidates("no-viable-candidates", ingredient, candidateDiag);
            if (recordBestEffortUnresolved(ctx, consumer, ingredient, count, "no-viable-candidates")) {
                return true;
            }
            recordFailureMissing(ctx, ingredient, missingCheckpoint);
            return false;
        }

        if (RSIntegrationMod.isDebugLoggingEnabled() && alive.size() > 1) {
            StringBuilder sb = new StringBuilder("[RSI-ensure] ").append(alive.size())
                    .append(" alive candidates for ").append(describeFirstItem(ingredient))
                    .append(" remaining=").append(remaining).append(":");
            for (AliveCandidate a : alive) {
                // 一并打印补齐剩余量需要几炉：块/锭/粒之争就是靠这个数字决定的，
                // 日志里没有它就只能看到「树选了块、实际烧了粒」却查不出原因。
                sb.append(" [").append(a.entry.recipe().getId())
                        .append(" netGain=").append(a.netGain)
                        .append(" perOp=").append(a.output.getCount())
                        .append(" ops=").append(CandidateEngine.estimatedOperations(
                                remaining, a.output.getCount()))
                        .append("]");
            }
            RSIntegrationMod.debug(sb.toString());
        }

        boolean ownsTwoPhase = ctx.bestEffort
                && !ctx.strictCandidatePass && !ctx.bestEffortFallbackPass;
        int candidatePasses = ownsTwoPhase ? 2 : 1;
        for (int candidatePass = 0; candidatePass < candidatePasses; candidatePass++) {
            boolean previousStrict = ctx.strictCandidatePass;
            boolean previousFallback = ctx.bestEffortFallbackPass;
            if (ownsTwoPhase) {
                ctx.strictCandidatePass = candidatePass == 0;
                ctx.bestEffortFallbackPass = candidatePass == 1;
                if (ctx.diagnosticsEnabled()) ctx.diag(candidatePass == 0
                        ? "ensureIngredient candidate pass=strict"
                        : "ensureIngredient candidate pass=best-effort-fallback");
            }
            try {
                for (AliveCandidate a : alive) {
            // Don't check timedOut() before the first candidate — the alive-building
            // loop may have exhausted the deadline with expensive result-item probes
            // for mod recipes. The first candidate is the highest-scored and is near-
            // certain to be a vanilla recipe with fast craftBatched.
            if (ctx.recipeCycles.containsBranch(a.entry.recipe().getId(), a.output)) {
                if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": cycle detected");
                continue;
            }

            StackKey outKey = StackKey.of(a.output, a.output.hasTag());
            if (ctx.recipeCycles.containsOutput(a.output)) {
                if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId()
                        + ": ancestor output cycle detected");
                continue;
            }

            if (requiresMissingSelfInput(a.entry, a.output, ctx)) {
                if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": self-input missing");
                continue;
            }

            // Ping-pong guard using StackKey (Item + NBT) to correctly distinguish
            // NBT-differentiated items like TACZ attachments (all tacz:attachment).
            Set<StackKey> inKeys = getRecipeInputKeys(a.entry, ctx.level.registryAccess());
            if (ctx.recipeCycles.containsAnyOutput(inKeys)) {
                if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId()
                        + ": input consumes an ancestor output");
                continue;
            }
            boolean isPingPong = false;
            for (StackKey inKey : inKeys) {
                if (edges.containsReverse(inKey, outKey)) {
                    isPingPong = true;
                    break;
                }
            }
            if (isPingPong) {
                if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId()
                        + ": ping-pong conversion cycle detected");
                continue;
            }

            // 被循环/自输入检查排除的候选无需构造供界面展示的备选列表。
            List<ResourceLocation> altIds = new ArrayList<>();
            List<String> altModTypes = new ArrayList<>();
            for (AliveCandidate other : selectableAlive) {
                if (other == a) continue;
                altIds.add(other.entry.recipe().getId());
                altModTypes.add(other.entry.modType().id());
            }

            long batches = Math.max(1L,
                    SaturatingLongMath.ceilDiv(remaining, a.netGain));
            int selfConsumed = selfConsumedPerBatch(a.entry, a.output,
                    ctx.level.registryAccess());

            ctx.beginUndo();
            edges.beginUndo();
            ctx.recipeCycles.enter(a.entry.recipe().getId(), a.output);
            Set<Set<Item>> conversionFamilies = a.entry.recipe() instanceof CraftingRecipe cr
                    ? NonProductiveTagConversionGuard.conversionFamilies(cr, a.output)
                    : Set.of();
            for (Set<Item> family : conversionFamilies) ctx.pushConversionFamily(family);

            boolean allOk;
            boolean activatedForcedRecipe = forceProduction
                    && ctx.activateForcedRecipe(a.entry.recipe().getId());
            try {
                for (StackKey inKey : inKeys) {
                    edges.addEdge(inKey, outKey);
                }
                long batchesLeft = batches;
                do {
                    int stageBatches = SaturatingLongMath.clampToInt(batchesLeft);
                    if (selfConsumed > 0) {
                        Ingredient exactOutput = ingredientOf(a.output, a.output.hasTag());
                        stageBatches = SaturatingLongMath.clampToInt(Math.min(batchesLeft,
                                ctx.countMatching(exactOutput) / selfConsumed));
                        if (stageBatches <= 0) {
                            if (ctx.diagnosticsEnabled()) {
                                ctx.diag("ensureIngredient bootstrap missing recipe="
                                        + a.entry.recipe().getId()
                                        + " output=" + describeFirstItem(exactOutput)
                                        + " available=" + ctx.countMatching(exactOutput)
                                        + " requiredSeed=" + selfConsumed);
                            }
                            allOk = false;
                            break;
                        }
                    }
                    allOk = StepExecutor.craftBatched(
                            a.entry, ctx, depth, altIds, altModTypes, edges, stageBatches, a.output);
                    batchesLeft -= stageBatches;
                } while (allOk && batchesLeft > 0);
            } finally {
                if (activatedForcedRecipe) {
                    ctx.deactivateForcedRecipe(a.entry.recipe().getId());
                }
                ctx.recipeCycles.leave(a.entry.recipe().getId(), a.output);
                for (Set<Item> family : conversionFamilies) ctx.popConversionFamily(family);
            }

            if (!allOk) {
                if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": craftOnce failed");
                ctx.rollback();
                edges.rollback();
                if (ctx.timedOut()) {
                    PerformanceMonitor.recordResolveTimeout();
                    if (recordBestEffortUnresolved(ctx, consumer, ingredient, count,
                            "timeout-after-craft-failure")) {
                        return true;
                    }
                    recordFailureMissing(ctx, ingredient, missingCheckpoint);
                    return false;
                }
                continue;
            }

            ResolutionContext.SupplyConsumption crafted = forceProduction
                    ? ctx.consumeProducedMatchingDetailed(ingredient, count)
                    : ctx.consumeMatchingDetailed(ingredient, count);
            if (crafted.complete()) {
                recordSupplyConsumption(ctx, consumer, consumedOut, crafted);
                if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient OK " + a.entry.recipe().getId());
                ctx.truncateMissing(missingCheckpoint);
                ctx.commitUndo();
                edges.commitUndo();
                return true;
            }

            if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": consumeMatching failed after craft");
            ctx.rollback();
            edges.rollback();
            if (ctx.timedOut()) {
                PerformanceMonitor.recordResolveTimeout();
                if (recordBestEffortUnresolved(ctx, consumer, ingredient, count,
                        "timeout-after-consume-failure")) {
                    return true;
                }
                recordFailureMissing(ctx, ingredient, missingCheckpoint);
                return false;
            }
                }
            } finally {
                ctx.strictCandidatePass = previousStrict;
                ctx.bestEffortFallbackPass = previousFallback;
            }
        }

        if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient FAILED: exhausted " + alive.size() + " viable candidates for "
                + describeFirstItem(ingredient));
        if (recordBestEffortUnresolved(ctx, consumer, ingredient, count, "candidates-exhausted")) {
            return true;
        }
        recordFailureMissing(ctx, ingredient, missingCheckpoint);
        return false;
    }

    static <T> List<T> forcedExecutionCandidates(List<T> viable,
                                                  Function<T, ResourceLocation> id,
                                                  @Nullable ResourceLocation forcedRecipeId) {
        if (forcedRecipeId == null) return viable;
        return viable.stream()
                .filter(candidate -> forcedRecipeId.equals(id.apply(candidate)))
                .toList();
    }

    static <T> List<T> prioritizeForcedCandidate(List<T> candidates,
                                                  Function<T, ResourceLocation> id,
                                                  @Nullable ResourceLocation forcedRecipeId) {
        if (forcedRecipeId == null || candidates.size() < 2) return candidates;
        List<T> ordered = new ArrayList<>(candidates.size());
        for (T candidate : candidates) {
            if (forcedRecipeId.equals(id.apply(candidate))) ordered.add(candidate);
        }
        for (T candidate : candidates) {
            if (!forcedRecipeId.equals(id.apply(candidate))) ordered.add(candidate);
        }
        return List.copyOf(ordered);
    }

    static List<IngredientSpec> coalesceRootSpecs(List<IngredientSpec> specs) {
        return StepExecutor.coalesceSpecsForGraph(specs);
    }

    static boolean recordBestEffortUnresolved(ResolutionContext ctx,
                                              @Nullable InputPortId consumer,
                                              Ingredient ingredient,
                                              long quantity,
                                              String reason) {
        if (!ctx.bestEffort || ctx.strictCandidatePass) return false;
        if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient UNRESOLVED: reason=" + reason + " consumer=" + consumer
                + " quantity=" + quantity + " ingredient=" + describeFirstItem(ingredient));
        recordUnresolved(ctx, consumer, ingredient, quantity);
        return true;
    }

    /**
     * 严格执行失败时只保留最深层缺料。若子配方已经记录了具体缺料，
     * 父配方不再用自身名称覆盖它。
     */
    static void recordFailureMissing(ResolutionContext ctx, Ingredient ingredient,
                                     int missingCheckpoint) {
        if (ctx.missingOut == null || ctx.missingCount() > missingCheckpoint) return;
        ctx.addMissing(describeFirstItem(ingredient));
    }

    /** 严格规划超时应立即失败；尽力准备超时应保留当前部分图。 */
    static boolean shouldAbortOnTimeout(boolean bestEffort) {
        return !bestEffort;
    }

    private static void recordUnresolved(ResolutionContext ctx, @Nullable InputPortId consumer,
                                         Ingredient ingredient, long quantity) {
        ctx.addMissing(describeFirstItem(ingredient));
        if (consumer != null) ctx.addUnresolved(consumer, ingredient, quantity);
    }

    private static void recordSupplyConsumption(ResolutionContext ctx,
                                                @Nullable InputPortId consumer,
                                                @Nullable List<ResolutionContext.SupplySlice> consumedOut,
                                                ResolutionContext.SupplyConsumption consumption) {
        if (consumer != null) {
            for (ResolutionContext.SupplySlice slice : consumption.slices()) {
                ctx.addAllocation(consumer, slice);
            }
        }
        if (consumedOut != null) consumedOut.addAll(consumption.slices());
    }

    private static Set<StackKey> getRecipeInputKeys(Recipe<?> recipe) {
        return getRecipeInputKeys(recipe, null);
    }

    private static Set<StackKey> getRecipeInputKeys(RecipeIndex.Entry entry,
                                                     @Nullable RegistryAccess access) {
        if (entry.recipe() instanceof CraftingRecipe) {
            return getRecipeInputKeys(entry.recipe(), access);
        }
        List<IngredientSpec> specs = recipeSpecs(entry, access);
        if (specs != null && !isIngredientDataBroken(specs)) {
            Set<StackKey> inputs = new HashSet<>();
            for (IngredientSpec spec : specs) {
                if (!spec.isEmpty()) addCycleGuardInputKeys(inputs, spec.ingredient());
            }
            return inputs;
        }
        return getRecipeInputKeys(entry.recipe(), access);
    }

    private static Set<StackKey> getRecipeInputKeys(Recipe<?> recipe,
                                                     @Nullable RegistryAccess access) {
        Set<StackKey> inputs = new HashSet<>();
        if (recipe instanceof CraftingRecipe cr) {
            for (Ingredient ing : cr.getIngredients()) {
                addCycleGuardInputKeys(inputs, ing);
            }
            return inputs;
        }
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        if (specs != null && !isIngredientDataBroken(specs)) {
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                addCycleGuardInputKeys(inputs, spec.ingredient());
            }
            return inputs;
        }
        // Standard path returned broken data — extract real inputs via reflection.
        if (access != null) {
            List<ItemStack> repaired = getRepairedInputStacks(recipe, access);
            for (ItemStack stack : repaired) {
                inputs.add(StackKey.of(stack, stack.hasTag()));
            }
        }
        return inputs;
    }

    /**
     * Add only unambiguous inputs to the ping-pong guard. An Ingredient backed by
     * a tag is an OR, not a requirement for every item returned by
     * {@link Ingredient#getItems()}. Adding every tag variant creates false edges
     * such as chiseled quartz -> quartz slab when the recipe actually consumed a
     * quartz block, causing a valid conversion chain to be rejected as cyclic.
     */
    private static void addCycleGuardInputKeys(Set<StackKey> inputs, Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) return;
        ItemStack[] options = ingredient.getItems();
        if (options.length == 0) return;

        Item firstItem = null;
        for (ItemStack option : options) {
            if (option.isEmpty()) continue;
            if (firstItem == null) {
                firstItem = option.getItem();
            } else if (option.getItem() != firstItem) {
                // Multiple item types mean this is an alternative ingredient.
                // The concrete option is selected later by the executor, so no
                // dependency edge can be inferred safely here.
                return;
            }
        }

        for (ItemStack option : options) {
            if (!option.isEmpty()) inputs.add(StackKey.of(option, option.hasTag()));
        }
    }

    /** Package-private probe used by the resolver regression tests. */
    static Set<StackKey> cycleGuardInputKeys(Ingredient ingredient) {
        Set<StackKey> inputs = new HashSet<>();
        addCycleGuardInputKeys(inputs, ingredient);
        return inputs;
    }

    private static boolean requiresMissingSelfInput(RecipeIndex.Entry entry,
                                                    ItemStack output, ResolutionContext ctx) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            return requiresMissingSelfInput(cr, output, ctx);
        }
        List<IngredientSpec> specs = recipeSpecs(entry, ctx.level.registryAccess());
        if (isIngredientDataBroken(specs)) {
            List<ItemStack> repaired = getRepairedInputStacks(entry.recipe(), ctx.level.registryAccess());
            for (ItemStack stack : repaired) {
                Ingredient ing = ingredientOf(stack, stack.hasTag());
                if (consumesOutput(ing, output) && ctx.countMatching(ing) <= 0) return true;
            }
            return false;
        }
        if (specs == null || specs.isEmpty()) return false;

        // Allow amplification recipes (output > self-consumed) even when
        // a self-matching ingredient is temporarily unavailable — recursive
        // ensureIngredient will either locate the base material or hit
        // cycle detection via the resolving set.
        int selfConsumed = 0;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            if (spec.role() != DemandRole.CATALYST && consumesOutput(spec.ingredient(), output)) {
                selfConsumed += spec.count();
            }
        }
        if (output.getCount() > selfConsumed) return false;

        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            if (consumesOutput(spec.ingredient(), output)
                    && ctx.countMatching(spec.ingredient()) <= 0)
                return true;
        }
        return false;
    }

    private static boolean requiresMissingSelfInput(CraftingRecipe recipe, ItemStack output, ResolutionContext ctx) {
        int selfConsumed = 0;
        boolean anyMissing = false;
        for (IngredientSpec spec : CraftPacketUtils.extractCraftingIngredientSpecs(recipe)) {
            if (spec.isEmpty()) continue;
            if (consumesOutput(spec.ingredient(), output)) {
                if (spec.role() != DemandRole.CATALYST) selfConsumed += spec.count();
                if (ctx.countMatching(spec.ingredient()) <= 0) anyMissing = true;
            }
        }
        // Amplification recipes (A + catalyst → n*A, n > 1): allow even when
        // the self-matching input is temporarily unavailable.
        if (output.getCount() > selfConsumed) return false;
        return anyMissing;
    }

    static int netGainPerBatch(RecipeIndex.Entry entry, ItemStack output,
                               RegistryAccess access) {
        long produced = declaredOutputCount(entry, output, access);
        long consumed = selfConsumedPerBatch(entry, output, access);
        long net = produced - consumed;
        return net <= 0L ? 0 : SaturatingLongMath.clampToInt(net);
    }

    static long declaredOutputCount(RecipeIndex.Entry entry, ItemStack output,
                                    RegistryAccess access) {
        ModRecipeHandler handler = ModRecipeHandlers.claimFor(entry.recipe()).winner();
        if (handler != null) {
            long total = 0L;
            for (var declaration : handler.getDeclaredOutputs(entry.recipe(), access)) {
                MaterialKey material = declaration.material();
                if (material == null
                        || !MaterialMatcher.equivalentRuntimeFragment(
                        material.toStack(1), output)) continue;
                long credited = declaration.probability().isGuaranteed()
                        ? declaration.minimumPerOperation()
                        : declaration.expectedPerOperation();
                total = SaturatingLongMath.add(total, credited);
            }
            if (total > 0L) return total;
        }
        return output.getCount();
    }

    private static int selfConsumedPerBatch(RecipeIndex.Entry entry, ItemStack output,
                                             RegistryAccess access) {
        if (entry.recipe() instanceof CraftingRecipe cr) {
            int selfConsumed = 0;
            for (IngredientSpec spec : CraftPacketUtils.extractCraftingIngredientSpecs(cr)) {
                if (spec.isEmpty() || spec.role() == DemandRole.CATALYST) continue;
                if (consumesOutput(spec.ingredient(), output)) {
                    selfConsumed += spec.count();
                }
            }
            return selfConsumed;
        }
        List<IngredientSpec> specs = recipeSpecs(entry, access);
        if (isIngredientDataBroken(specs)) {
            int selfConsumed = 0;
            List<ItemStack> repaired = getRepairedInputStacks(entry.recipe(), access);
            for (ItemStack stack : repaired) {
                Ingredient ing = ingredientOf(stack, stack.hasTag());
                if (consumesOutput(ing, output)) selfConsumed += stack.getCount();
            }
            return selfConsumed;
        }
        if (specs == null || specs.isEmpty()) return 0;
        int selfConsumed = 0;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            if (spec.role() != DemandRole.CATALYST
                    && consumesOutput(spec.ingredient(), output))
                selfConsumed += spec.count();
        }
        return selfConsumed;
    }

    /**
     * 只有输入标签的全部可选物都是该输出时，才视为自消耗。
     * 例如 forge:seeds 同时包含 magebloom_crop 和普通种子，
     * seed -> magebloom_crop 不是净收益为零的循环。
     */
    static boolean consumesOutput(Ingredient ingredient, ItemStack output) {
        if (ingredient == null || ingredient.isEmpty()
                || output == null || output.isEmpty()) return false;
        if (!IngredientMatcher.hasCompleteItemList(ingredient)) {
            return IngredientMatcher.test(ingredient, output);
        }
        int candidates = 0;
        for (ItemStack template : ingredient.getItems()) {
            if (template.isEmpty()) continue;
            candidates++;
            if (template.getItem() != output.getItem()) return false;
        }
        return candidates > 0;
    }

    private static List<IngredientSpec> recipeSpecs(RecipeIndex.Entry entry,
                                                     @Nullable RegistryAccess access) {
        List<IngredientSpec> specs =
                CraftPacketUtils.extractSingleOperationRecursiveIngredientSpecs(entry.recipe());
        return MinersDelightCopperPotSupport.adaptIngredientSpecs(
                entry.modType(), specs, entry.recipe(), access);
    }

    public static List<ItemStack> stacksFromKeyedCounts(Map<StackKey, ? extends Number> counts) {
        List<ItemStack> list = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            long remaining = Math.max(0L, entry.getValue().longValue());
            while (remaining > 0L) {
                int count = SaturatingLongMath.clampToInt(remaining);
                StackKey key = entry.getKey();
                ItemStack stack = new ItemStack(key.item(), count);
                if (key.tag() != null) {
                    try {
                        stack.setTag(TagParser.parseTag(key.tag()));
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] NBT parse failed", e); }
                }
                list.add(stack);
                remaining -= count;
            }
        }
        return list;
    }

    private static Map<ResourceLocation, ResourceLocation> buildPreferredRecipes(Level level) {
        List<? extends String> ids = RSIntegrationConfig.PREFERRED_RECIPES.get();
        if (ids.isEmpty()) return null;

        Map<ResourceLocation, ResourceLocation> map = new HashMap<>();
        RecipeManager rm = level.getRecipeManager();
        for (String idStr : ids) {
            ResourceLocation id = ResourceLocation.tryParse(idStr);
            if (id == null) continue;
            Recipe<?> recipe = rm.byKey(id).orElse(null);
            ItemStack result;
            if (recipe instanceof CraftingRecipe cr) {
                result = cr.getResultItem(level.registryAccess());
            } else if (recipe != null) {
                result = ModRecipeHandlers.tryGetResultItem(recipe, level.registryAccess());
            } else {
                continue;
            }
            if (!result.isEmpty()) {
                ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(result.getItem());
                if (itemKey != null) {
                    map.putIfAbsent(itemKey, id);
                }
            }
        }
        return map.isEmpty() ? null : map;
    }

    private static Map<ResourceLocation, ResourceLocation> mergeForcedOverrides(
            Level level, @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides) {
        Map<ResourceLocation, ResourceLocation> prefs = buildPreferredRecipes(level);
        if (forcedOverrides == null || forcedOverrides.isEmpty()) return prefs;
        if (prefs == null) return new HashMap<>(forcedOverrides);
        prefs.putAll(forcedOverrides);
        return prefs;
    }

    private static String describeItem(ItemStack stack) {
        if (stack.isEmpty()) return "???";
        return stack.getDescriptionId();
    }

    /** 打印被过滤掉的候选及原因，用来对照「计划树里明明有这条配方，执行时却不见了」。 */
    private static void logSkippedCandidates(String phase, Ingredient ingredient,
                                             @Nullable List<CandidateEngine.CandidateDiagnostic> diag) {
        if (diag == null || diag.isEmpty()) return;
        StringBuilder sb = new StringBuilder("[RSI-ensure] ")
                .append(phase).append(" skipped=").append(diag.size())
                .append(" for ").append(describeFirstItem(ingredient)).append(":");
        for (CandidateEngine.CandidateDiagnostic d : diag) {
            sb.append(" [").append(d.recipeId()).append(" -> ").append(d.skipReason()).append("]");
        }
        RSIntegrationMod.debug(sb.toString());
    }

    private static String describeFirstItem(Ingredient ingredient) {
        String base = null;
        ItemStack first = ItemStack.EMPTY;
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) {
                base = stack.getDescriptionId();
                first = stack;
                break;
            }
        }
        if (base == null) return "???";
        // 流体需求在 RSI 里是同一个凭据物品，真正的流体身份只写在 NBT 里；
        // 日志不带流体名时，多种流体的候选列表混在一起根本分不清哪条对应哪种流体。
        if (InkFluidSupport.isToken(first)) {
            FluidStack fluid = InkFluidSupport.fluid(first);
            ResourceLocation fluidId = fluid.isEmpty()
                    ? null : ForgeRegistries.FLUIDS.getKey(fluid.getFluid());
            if (fluidId != null) base = base + "[" + fluidId + "]";
        }
        String nbtHint = SlashBladeRecipeHandler
                .describeNbtRequirements(ingredient);
        return nbtHint != null ? base + nbtHint : base;
    }

    private static boolean isTaintedEarthHeartRequirement(Ingredient ingredient) {
        for (ItemStack stack : ingredient.getItems()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (EARTH_HEART_ID.equals(id) && stack.getTag() != null
                    && stack.getTag().getBoolean("isTainted")) return true;
        }
        return false;
    }

    public record ResolutionStep(
            ResourceLocation recipeId,
            ModType modType,
            @Nullable ResourceLocation recipeTypeId,
            List<ResourceLocation> alternativeIds,
            List<String> alternativeModTypes,
            boolean inferMode,
            int executions,
            @Nullable ItemStack syntheticInput,
            @Nullable ItemStack syntheticOutput,
            @Nullable ProductionTarget productionTarget,
            List<MagicResourceCost> magicCosts
    ) {
        public ResolutionStep {
            alternativeIds = List.copyOf(alternativeIds);
            alternativeModTypes = List.copyOf(alternativeModTypes);
            if (executions < 1) executions = 1;
            syntheticInput = syntheticInput == null ? null : syntheticInput.copyWithCount(1);
            syntheticOutput = syntheticOutput == null ? null : syntheticOutput.copyWithCount(1);
            magicCosts = MagicResourceCost.merge(magicCosts);
        }
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId,
                              List<ResourceLocation> alternativeIds,
                              List<String> alternativeModTypes,
                              boolean inferMode, int executions,
                              @Nullable ItemStack syntheticInput, @Nullable ItemStack syntheticOutput,
                              @Nullable ProductionTarget productionTarget) {
            this(recipeId, modType, recipeTypeId, alternativeIds, alternativeModTypes,
                    inferMode, executions, syntheticInput, syntheticOutput, productionTarget, List.of());
        }
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId,
                              List<ResourceLocation> alternativeIds,
                              List<String> alternativeModTypes,
                              boolean inferMode, int executions,
                              @Nullable ItemStack syntheticInput, @Nullable ItemStack syntheticOutput) {
            this(recipeId, modType, recipeTypeId, alternativeIds, alternativeModTypes,
                    inferMode, executions, syntheticInput, syntheticOutput, null);
        }
        /** Backward-compatible: explicit executions. */
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId,
                              List<ResourceLocation> alternativeIds,
                              List<String> alternativeModTypes,
                              boolean inferMode, int executions) {
            this(recipeId, modType, recipeTypeId, alternativeIds, alternativeModTypes,
                    inferMode, executions, null, null);
        }
        /** Backward-compatible: single execution. */
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId,
                              List<ResourceLocation> alternativeIds,
                              List<String> alternativeModTypes,
                              boolean inferMode) {
            this(recipeId, modType, recipeTypeId, alternativeIds, alternativeModTypes, inferMode, 1, null, null);
        }
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId) {
            this(recipeId, modType, recipeTypeId, List.of(), List.of(), false, 1, null, null);
        }
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId,
                              boolean inferMode) {
            this(recipeId, modType, recipeTypeId, List.of(), List.of(), inferMode, 1, null, null);
        }
        public ResolutionStep(ResourceLocation recipeId, ModType modType,
                              @Nullable ResourceLocation recipeTypeId,
                              List<ResourceLocation> alternativeIds,
                              List<String> alternativeModTypes) {
            this(recipeId, modType, recipeTypeId, alternativeIds, alternativeModTypes, false, 1, null, null);
        }

        public ResolutionStep withExecutions(int updatedExecutions) {
            if (updatedExecutions == executions) return this;
            return new ResolutionStep(recipeId, modType, recipeTypeId,
                    alternativeIds, alternativeModTypes, inferMode, updatedExecutions,
                    syntheticInput, syntheticOutput, productionTarget, magicCosts);
        }
    }

    public record TraceEntry(ResourceLocation recipeId, ModType modType, int score,
                              boolean skipped, String reason) {}

    // ── Hidden NBT output extraction ──────────────────────────────────
    //
    // Some mods (TACZ, Applied Armorer) have getResultItem() return a bare
    // item without NBT while the real crafted output (with AttachmentId etc.)
    // lives in a private ItemStack field.  Scan every ItemStack field on
    // the recipe class and return the first one that carries NBT — that is
    // almost certainly the real output the mod author intended.

    public static ItemStack extractHiddenOutput(Recipe<?> recipe) {
        return extractHiddenOutput(recipe, ItemStack.EMPTY);
    }

    /**
     * @param visibleResult 该配方对外的可见产物；非空时只接受与它同物品的隐藏栈，
     *                      避免把材料/支付物这类同样带 NBT 的字段误当成产物。
     */
    public static ItemStack extractHiddenOutput(Recipe<?> recipe, ItemStack visibleResult) {
        ResourceLocation id = recipe.getId();
        boolean identityKnown = visibleResult != null && !visibleResult.isEmpty();
        ItemStack cached = HIDDEN_OUTPUT_CACHE.get(id);
        if (cached != null) {
            // 缓存里的值只有在物品与可见产物一致时才是真产物。
            if (cached.isEmpty() || !identityKnown
                    || cached.getItem() == visibleResult.getItem()) {
                return cached.copy();
            }
        }

        Class<?> clazz = recipe.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getType() != ItemStack.class) continue;
                f.setAccessible(true);
                try {
                    ItemStack stack = (ItemStack) f.get(recipe);
                    if (stack != null && !stack.isEmpty() && stack.hasTag()
                            && (!identityKnown || stack.getItem() == visibleResult.getItem())) {
                        ItemStack result = stack.copy();
                        HIDDEN_OUTPUT_CACHE.put(id, result.copy());
                        return result;
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Resolver] reflection read failed", e); }
            }
            clazz = clazz.getSuperclass();
        }
        // Cache negative result too so we don't re-scan recipes with no hidden output
        HIDDEN_OUTPUT_CACHE.put(id, ItemStack.EMPTY);
        return ItemStack.EMPTY;
    }

    /** Output identity used by planning; runtime-derived NBT remains unspecified. */
    public static ItemStack resolveDeclaredOutput(Recipe<?> recipe, ItemStack staticResult) {
        return resolveDeclaredOutput(recipe, staticResult,
                ModRecipeHandlers.claimFor(recipe).winner());
    }

    static ItemStack resolveDeclaredOutput(
            Recipe<?> recipe, ItemStack staticResult,
            @Nullable ModRecipeHandler handler) {
        if (staticResult.isEmpty()) return ItemStack.EMPTY;
        if (handler != null && handler.hasRuntimeDependentPrimaryNbt(recipe)) {
            ItemStack dynamic = staticResult.copy();
            dynamic.setTag(null);
            return dynamic;
        }
        // 虚拟交换配方（市场交易等）的产物就是字段里写死的可见结果，支付物同样以
        // ItemStack 字段保存；反射扫描会把支付物当成“隐藏产物”（例如把护手当成
        // 探险家指南针），导致节点声明的产物与实际产出不符，执行时必然报
        // OUTPUT_MISSING。这类配方的产物是权威的，直接使用可见结果。
        if (!staticResult.hasTag() && !(recipe instanceof VirtualExchangeRecipe)) {
            ItemStack hidden = extractHiddenOutput(recipe, staticResult);
            if (!hidden.isEmpty()) return hidden;
        }
        return staticResult.copy();
    }

    // ── Broken ingredient repair ───────────────────────────────────
    //
    // Some mods (TACZ, Applied Armorer) implement getIngredients() by
    // returning bare items without NBT or even AIR placeholders, while
    // the real ingredient data lives in private fields.  When the
    // standard extraction path produces garbage, these helpers detect
    // it and fall back to reflection-based field scanning.

    private static boolean isIngredientDataBroken(List<IngredientSpec> specs) {
        if (specs == null || specs.isEmpty()) return true;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            for (ItemStack stack : spec.ingredient().getItems()) {
                if (stack.isEmpty()) continue;
                if (stack.getItem() == Items.AIR) return true;
                ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (rl != null && !stack.hasTag()) {
                    String ns = rl.getNamespace();
                    if ("tacz".equals(ns) || "applied_armorer".equals(ns)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Extract real ingredient stacks from a recipe whose standard
     * {@code getIngredients()} / {@code extractIngredientSpecs()} path
     * returned broken data.  Scans private {@code ItemStack} and
     * {@code List<ItemStack>} fields, skipping fields that match the
     * recipe output.
     */
    public static List<ItemStack> getRepairedInputStacks(Recipe<?> recipe, RegistryAccess access) {
        ItemStack output = ModRecipeHandlers.tryGetResultItem(recipe, access);
        output = resolveDeclaredOutput(recipe, output);

        List<ItemStack> repaired = new ArrayList<>();
        Class<?> clazz = recipe.getClass();

        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (name.contains("out") || name.contains("result")) continue;

                f.setAccessible(true);
                try {
                    Object val = f.get(recipe);
                    if (val instanceof ItemStack stack) {
                        if (!stack.isEmpty() && stack.getItem() != Items.AIR) {
                            if (!MaterialMatcher.equivalentRuntimeFragment(stack, output)) {
                                repaired.add(stack.copy());
                            }
                        }
                    } else if (val instanceof List<?> list) {
                        for (Object elem : list) {
                            if (elem instanceof ItemStack stack) {
                                if (!stack.isEmpty() && stack.getItem() != Items.AIR) {
                                    if (!MaterialMatcher.equivalentRuntimeFragment(stack, output)) {
                                        repaired.add(stack.copy());
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Resolver] reflection read failed", e); }
            }
            clazz = clazz.getSuperclass();
        }
        return repaired;
    }

    // ── Trace ──────────────────────────────────────────────────────

    public static List<TraceEntry> traceCandidates(Ingredient ingredient,
                                                    Level level,
                                                    @Nullable ServerPlayer player) {
        return traceCandidates(ingredient, level, player, Map.of());
    }

    public static List<TraceEntry> traceCandidates(Ingredient ingredient,
                                                    Level level,
                                                    @Nullable ServerPlayer player,
                                                    Map<StackKey, ? extends Number> available) {
        ResolutionContext ctx = new ResolutionContext(level,
                RecipeIndex.get(level), available, null, player, null, false, null);
        List<CandidateEngine.CandidateDiagnostic> raw = new ArrayList<>();
        CandidateEngine.findCandidates(ingredient, ctx, raw);
        List<TraceEntry> out = new ArrayList<>();
        for (var d : raw) {
            out.add(new TraceEntry(d.recipeId(), d.modType(), d.score(), d.skipped(), d.skipReason()));
        }
        return out;
    }

    static Ingredient ingredientOf(ItemStack stack) {
        return ingredientOf(stack, false);
    }

    static Ingredient ingredientOf(ItemStack stack, boolean strictNbt) {
        if (strictNbt && stack.hasTag()) {
            return StrictNBTIngredient.of(stack.copy());
        }
        if (stack.hasTag()) {
            return Ingredient.of(new ItemStack(stack.getItem()));
        }
        return Ingredient.of(stack);
    }

    public record StackKey(Item item, @Nullable String tag) {
        public StackKey {
            tag = CanonicalNbt.normalizeSnbt(tag);
        }

        static StackKey of(ItemStack stack, boolean includeNbt) {
            Item item = stack.getItem();
            String tag = includeNbt ? CanonicalNbt.exactSnbt(stack.getTag()) : null;
            return new StackKey(item, tag);
        }

        public ItemStack toStack() {
            ItemStack stack = new ItemStack(item);
            if (tag != null) {
                try {
                    stack.setTag(TagParser.parseTag(tag));
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] NBT parse failed", e); }
            }
            return stack;
        }
    }

    /** Tracks directed conversion edges (StackKey → StackKey) with transactional undo/redo.
     *  Using StackKey (Item + NBT) instead of plain Item prevents false ping-pong
     *  detection for NBT-differentiated items like TACZ attachments. */
    public static class EdgeTracker {
        private final Set<String> activeEdges = new HashSet<>();
        private final List<List<String>> history = new ArrayList<>();

        public void beginUndo() {
            history.add(new ArrayList<>());
        }

        public void commitUndo() {
            if (history.size() > 1) {
                List<String> current = history.remove(history.size() - 1);
                history.get(history.size() - 1).addAll(current);
            }
            // When history.size() == 1 (outermost level), edges stay in activeEdges
            // as committed — they prevent reverse edges across the entire plan.
            // The level is kept as a permanent baseline so future rollback() calls
            // only undo edges added by subsequent beginUndo() pushes.
        }

        public void rollback() {
            if (!history.isEmpty()) {
                List<String> current = history.remove(history.size() - 1);
                activeEdges.removeAll(current);
            }
        }

        public void addEdge(StackKey in, StackKey out) {
            ResourceLocation inRl = ForgeRegistries.ITEMS.getKey(in.item());
            ResourceLocation outRl = ForgeRegistries.ITEMS.getKey(out.item());
            if (inRl == null || outRl == null) return;

            String inStr = inRl + (in.tag() != null ? "|" + in.tag() : "");
            String outStr = outRl + (out.tag() != null ? "|" + out.tag() : "");

            String edge = inStr + "->" + outStr;
            if (activeEdges.add(edge) && !history.isEmpty()) {
                history.get(history.size() - 1).add(edge);
            }
        }

        public boolean containsReverse(StackKey in, StackKey out) {
            ResourceLocation inRl = ForgeRegistries.ITEMS.getKey(in.item());
            ResourceLocation outRl = ForgeRegistries.ITEMS.getKey(out.item());
            if (inRl == null || outRl == null) return false;

            String inStr = inRl + (in.tag() != null ? "|" + in.tag() : "");
            String outStr = outRl + (out.tag() != null ? "|" + out.tag() : "");

            return activeEdges.contains(outStr + "->" + inStr);
        }
    }
}
