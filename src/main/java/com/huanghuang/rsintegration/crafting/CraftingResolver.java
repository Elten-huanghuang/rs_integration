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
import com.huanghuang.rsintegration.recipe.RecipeOutputSpec;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
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

    public static CraftPlanGraph resolveTypedPreviewGraph(
            List<ScaledIngredientSpec> needed, Map<StackKey, ? extends Number> availableKeyed, Level level,
            @Nullable ServerPlayer player, @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut, @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean singleVariantRoots, int timeoutMs, @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks, PlanningPreferences preferences) {
        return startTypedPreview(needed, availableKeyed, level, player, storageEndpoint, missingOut,
                forcedOverrides, singleVariantRoots, timeoutMs, activeRoot, materialLocks, preferences)
                .finishSynchronously();
    }

    public static TypedPreview startTypedPreview(
            List<ScaledIngredientSpec> needed, Map<StackKey, ? extends Number> availableKeyed, Level level,
            @Nullable ServerPlayer player, @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut, @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides,
            boolean singleVariantRoots, int timeoutMs, @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks, PlanningPreferences preferences) {
        return startRootResolution(rootSpecs(needed), availableKeyed, level, player, storageEndpoint,
                missingOut, forcedOverrides, true, singleVariantRoots, timeoutMs, activeRoot,
                materialLocks, preferences.excludedRecipes(), preferences, true);
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
    static TypedPreview startSupplementalGraph(List<IngredientSpec> needed,
            Map<StackKey, ? extends Number> available, Level level, ServerPlayer player, CraftStorageEndpoint storageEndpoint,
            List<String> missing, Set<ResourceLocation> excludedRecipes,
            Map<ResourceLocation, ResourceLocation> forcedRecipes, Map<String, ItemStack> materialLocks,
            ActiveRootRecipe activeRoot) {
        return startRootResolution(ingredientRootSpecs(needed), available, level, player, storageEndpoint,
                missing, forcedRecipes, false, true, null, activeRoot, materialLocks, excludedRecipes,
                PlanningPreferences.empty(), false);
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
        return startRootResolution(needed, availableKeyed, level, player, storageEndpoint, missingOut,
                forcedOverrides, bestEffort, singleVariantRoots, timeoutMs, activeRoot,
                materialLocks, excludedRecipes, preferences, false).finishSynchronously();
    }

    private static TypedPreview startRootResolution(
            List<RootSpec> needed, Map<StackKey, ? extends Number> availableKeyed, Level level,
            @Nullable ServerPlayer player, @Nullable CraftStorageEndpoint storageEndpoint,
            @Nullable List<String> missingOut,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides, boolean bestEffort,
            boolean singleVariantRoots, @Nullable Integer timeoutMs, @Nullable ActiveRootRecipe activeRoot,
            Map<String, ItemStack> materialLocks, Set<ResourceLocation> excludedRecipes,
            PlanningPreferences preferences, boolean checkPreviewBudget) {
        Map<ResourceLocation, ResourceLocation> prefs = mergeForcedOverrides(level, forcedOverrides);
        Map<Item, List<RecipeIndex.Entry>> index = RecipeIndex.get(level);
        // 补料由当前 tick 的共享预算逐帧推进；保留步数、深度和循环约束，没有跨轮的墙钟截止时间。
        long deadline = timeoutMs == null ? Long.MAX_VALUE : timeoutMs > 0
                ? ResolutionContext.deadlineAfterMillis(timeoutMs) : ResolutionContext.resolveDeadlineNanos();
        ResolutionContext ctx = new ResolutionContext(level, index, availableKeyed, prefs, forcedOverrides,
                player, storageEndpoint, bestEffort, missingOut, deadline,
                timeoutMs != null && timeoutMs > 0 && shouldAbortOnTimeout(bestEffort));
        ctx.withMaterialLocks(materialLocks);
        ctx.withPreferences(preferences);
        // 补料还要排除任务中未结束的配方，不能被空的持久偏好覆盖。
        ctx.excludedRecipes = Set.copyOf(excludedRecipes);
        EdgeTracker edges = new EdgeTracker();
        Map<Integer, RootDemand> rootsByOrder = new TreeMap<>();
        PreviewCheckpoint checkpoint = checkPreviewBudget ? new PreviewCheckpoint() : null;
        var stack = new PlanningStack();
        var job = stack.<List<RootDemand>>start(done -> {
            if (activeRoot != null && !activeRoot.output().isEmpty()) {
                ctx.recipeCycles.enter(activeRoot.recipeId(), activeRoot.output());
                stack.enter(() -> ctx.recipeCycles.leave(activeRoot.recipeId(), activeRoot.output()));
            }
            boolean rootActive = activeRoot != null && !activeRoot.output().isEmpty();
            // Crafting-grid roots are fungible planning demand and may be coalesced.
            // Machine roots are physical input slots and must retain their boundaries.
            List<RootSpec> rootsToResolve = new ArrayList<>(singleVariantRoots
                    ? machineRootDemands(needed)
                    : coalesceRootDemands(needed));
            if (!singleVariantRoots) {
                // Resolve reusable catalysts after ordinary inputs. This lets an
                // intermediate CraftTweaker recipe return a shared catalyst before
                // the terminal root reserves it.
                rootsToResolve.sort(rootSpecComparator());
            }
            List<ScaledIngredientSpec> pendingRoots = new ArrayList<>();
            for (RootSpec root : rootsToResolve) {
                Ingredient input = activeRoot == null ? root.ingredient()
                        : ctx.lockedIngredient(activeRoot.recipeId(), root.ingredient());
                pendingRoots.add(new ScaledIngredientSpec(input, root.count(), root.consumption()));
            }
            ctx.pendingInputs.push(pendingRoots);
            stack.enter(ctx.pendingInputs::pop);
            class Roots implements Runnable {
                private int index;

                @Override
                public void run() {
                    if (index >= rootsToResolve.size()) {
                        finish();
                        return;
                    }
                    RootSpec root = rootsToResolve.get(index++);
                    ctx.previewRootSlot = root.order();
                    Ingredient ingredient = pendingRoots.remove(0).ingredient();
                    if (root.isEmpty()) {
                        stack.resume(this);
                        return;
                    }
                    RootSpec spec = new RootSpec(ingredient, root.count(), root.consumption(), root.order());
                    List<ResolutionContext.SupplySlice> consumed = new ArrayList<>();
                    int rootMissingCheckpoint = ctx.missingCount();
                    Consumer<Ingredient> resolvedRoot = selected -> {
                    boolean resolved = selected != null;
                    Ingredient resolvedIngredient = resolved ? selected : ingredient;
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
                    RootDemand demand = new RootDemand(
                            resolvedIngredient, spec.count(), missing,
                            firstDisplayStack(resolvedIngredient), allocations, spec.role());
                    rootsByOrder.put(spec.order(), demand);
                    if (checkpoint != null) checkpoint.completedRoot(ctx, spec.order(), demand);
                    if (!resolved && !bestEffort) finish();
                    else stack.resume(this);
                    };
                    if (singleVariantRoots) {
                        StepExecutor.ensureSingleVariantMachineInput(ingredient, spec.count(), ctx, 0,
                                edges, null, consumed, stack, resolvedRoot);
                    } else {
                        ensureIngredient(ingredient, spec.count(), ctx, 0, edges, null, consumed,
                                stack, resolved -> resolvedRoot.accept(resolved ? ingredient : null));
                    }
                }

                private void finish() {
                    stack.leave();
                    if (rootActive) stack.leave();
                    stack.complete(done, new ArrayList<>(rootsByOrder.values()));
                }
            }
            stack.resume(new Roots());
        });
        return new TypedPreview(ctx, job, checkpoint);
    }

    /** 只保留已结算根对应的前缀。当前根可能还在试探配方，不能发布它的材料边。 */
    private static final class PreviewCheckpoint {
        private static final int MAX_NODES = RSIntegrationConfig.MIN_CRAFTING_MAX_GRAPH_NODES;
        private static final int MAX_DETAILS = 4096;
        private final Map<Integer, RootDemand> roots = new TreeMap<>();
        private int nodes;
        private int edges;
        private int unresolved;
        private long nodeDetails;
        private long rootDetails;
        private boolean frozen;

        void completedRoot(ResolutionContext context, int order, RootDemand root) {
            if (frozen) return;
            if (context.timeoutReached()
                    || context.graphNodes.size() > MAX_NODES || roots.size() == MAX_NODES) {
                frozen = true;
                return;
            }
            if (root.unresolvedQuantity() > 0 || context.graphUnresolved.size() > unresolved) {
                try {
                    context.requireKnownPreviewBudget();
                } catch (CraftingPlanningTimeoutException | CraftingPlanningLimitException exhausted) {
                    frozen = true;
                    return;
                }
            }
            long details = nodeDetails;
            for (int i = nodes; i < context.graphNodes.size(); i++) {
                CraftNode node = context.graphNodes.get(i);
                details += (long) node.inputs().size() + node.outputs().size() + node.alternativeIds().size();
            }
            long rootSize = rootDetails + 1L + root.allocations().size();
            if (details + rootSize + context.graphAllocations.size() + context.graphUnresolved.size()
                    > MAX_DETAILS) {
                frozen = true;
                return;
            }
            roots.put(order, root);
            nodes = context.graphNodes.size();
            edges = context.graphAllocations.size();
            unresolved = context.graphUnresolved.size();
            nodeDetails = details;
            rootDetails = rootSize;
        }

        CraftPlanGraph graph(ResolutionContext context) {
            List<CraftNode> prefix = context.graphNodes.subList(0, nodes);
            return new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                    prefix, context.graphAllocations.subList(0, edges), new ArrayList<>(roots.values()),
                    context.graphUnresolved.subList(0, unresolved), prefix.stream().map(CraftNode::id).toList());
        }
    }

    /** 必须在服务器线程推进；等待下一 tick 的时间不计入求解预算。 */
    public static final class TypedPreview {
        private final ResolutionContext context;
        private final PlanningStack.Job<List<RootDemand>> job;
        @Nullable
        private final PreviewCheckpoint checkpoint;
        private long suspendedAt;

        private TypedPreview(ResolutionContext context, PlanningStack.Job<List<RootDemand>> job,
                             @Nullable PreviewCheckpoint checkpoint) {
            this.context = context;
            this.job = job;
            this.checkpoint = checkpoint;
            this.suspendedAt = System.nanoTime();
        }

        public boolean advance(long sliceNanos) {
            return advance(sliceNanos, () -> true);
        }

        boolean advance(long sliceNanos, BooleanSupplier allowed) {
            long now = System.nanoTime();
            if (suspendedAt != 0L && context.deadlineNanos != Long.MAX_VALUE) context.deadlineNanos += now - suspendedAt;
            suspendedAt = 0L;
            // 实际机器状态可能在两个切片之间改变，不跨切片复用其可用性。
            context.beginCandidateSlice();
            if (job.advance(sliceNanos, () -> allowed.getAsBoolean()
                    && (checkpoint == null || !context.timedOut()))) return true;
            if (checkpoint != null && context.timeoutReached()) {
                job.cancel();
                throw new CraftingPlanningTimeoutException();
            }
            suspendedAt = System.nanoTime();
            return false;
        }

        void cancel() {
            job.cancel();
        }

        private CraftPlanGraph finishSynchronously() {
            advance(Long.MAX_VALUE);
            return graph();
        }

        public String timeoutSummary() {
            return "rootSlot=" + context.timeoutRootSlot + " ensureCalls=" + context.ensureCalls
                    + " attemptedRecipeTypes=" + context.busiestPreviewTypes();
        }

        /** 超时草稿仅用于展示，不走执行图的校验、压缩或并行机器估算。 */
        public CraftPlanGraph completedRootsGraph() {
            if (checkpoint == null) throw new IllegalStateException("仅类型预览保留展示进度");
            return checkpoint.graph(context);
        }

        public CraftPlanGraph graph() {
            ResolutionContext ctx = context;
            List<RootDemand> roots = job.result();
            if (checkpoint != null && (!ctx.graphUnresolved.isEmpty()
                    || roots.stream().anyMatch(root -> root.unresolvedQuantity() > 0))) {
                // 已找到完整替代路径时，早先失败的候选不应使整份计划失败。
                ctx.requireKnownPreviewBudget();
            }
            CraftPlanGraph graph = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                    ctx.graphNodes, ctx.graphAllocations, roots, ctx.graphUnresolved,
                    ctx.graphNodes.stream().map(CraftNode::id).toList());
            CraftPlanValidator.validate(graph);
            graph = CraftPlanGraphCompactor.compact(graph, CraftNodeMergePolicy.forLevel(ctx.level));
            ctx.logGuardSummary();
            ctx.dumpDiagnostics();
            return graph;
        }
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
        var stack = new PlanningStack();
        return stack.resolve(done -> ensureIngredient(ingredient, count, ctx, depth, edges,
                consumer, consumedOut, stack, done));
    }

    static void ensureIngredient(Ingredient ingredient, long count, ResolutionContext ctx, int depth,
                                 EdgeTracker edges, @Nullable InputPortId consumer,
                                 @Nullable List<ResolutionContext.SupplySlice> consumedOut,
                                 PlanningStack stack, Consumer<Boolean> done) {
        prepareSharedAmplification(ingredient, count, ctx, depth, edges, stack,
                () -> resolveIngredient(ingredient, count, ctx, depth, edges, consumer,
                        consumedOut, false, stack, done));
    }

    private static void resolveIngredient(Ingredient ingredient, long count, ResolutionContext ctx,
                                            int depth, EdgeTracker edges,
                                            @Nullable InputPortId consumer,
                                            @Nullable List<ResolutionContext.SupplySlice> consumedOut,
                                            boolean prepareOnly, PlanningStack stack, Consumer<Boolean> done) {
        if (count <= 0) {
            stack.complete(done, true);
            return;
        }
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
        if (!forceProduction && mayConsumeDirect
                && (!prepareOnly || ctx.countMatching(ingredient) >= count)) {
            ResolutionContext.SupplyConsumption direct =
                    ctx.consumeMatchingDetailed(ingredient, prepareOnly ? 0L : count);
            if (direct.complete()) {
                recordSupplyConsumption(ctx, consumer, consumedOut, direct);
                ctx.commitUndo();
                edges.commitUndo();
                stack.complete(done, true);
                return;
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
            finishMissing(ctx, consumer, ingredient, count, "timeout-before-candidates", missingCheckpoint, stack, done);
            return;
        }
        if (++ctx.ensureCalls > maxEnsureCalls()) {
            ctx.ensureLimitReached = true;
            if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient guard ENSURE_CALLS calls=" + ctx.ensureCalls
                    + " max=" + maxEnsureCalls() + " depth=" + depth + " count=" + count);
            stack.complete(done, false);
            return;
        }

        long alreadyHave = forceProduction ? 0L : ctx.countMatching(ingredient);
        long consumeMs = (System.nanoTime() - consumeStart) / 1_000_000;
        long remaining = count - alreadyHave;
        if (!forceProduction && remaining > 0 && isTaintedEarthHeartRequirement(ingredient)) {
            Ingredient plainHeart = Ingredient.of(ForgeRegistries.ITEMS.getValue(EARTH_HEART_ID));
            NodeId nodeId = ctx.allocateNodeId();
            InputPortId inputPort = new InputPortId(nodeId, 0);
            if (plainHeart.getItems().length > 0) {
                ensureIngredient(plainHeart, remaining, ctx, depth + 1, edges, inputPort, null, stack, resolved -> {
                if (resolved) {
                ItemStack plainTemplate = plainHeart.getItems()[0].copyWithCount(1);
                ItemStack taintedTemplate = ingredient.getItems().length > 0
                        ? ingredient.getItems()[0].copyWithCount(1) : ItemStack.EMPTY;
                if (taintedTemplate.isEmpty()) {
                    stack.complete(done, false);
                    return;
                }
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
                }
                resolveCandidates(ingredient, count, ctx, depth, edges, consumer, consumedOut, prepareOnly,
                        count - ctx.countMatching(ingredient), minReserve, forcedRecipeId, false,
                        missingCheckpoint, consumeMs, stack, done);
                });
                return;
            }
        }
        resolveCandidates(ingredient, count, ctx, depth, edges, consumer, consumedOut, prepareOnly,
                remaining, minReserve, forcedRecipeId, forceProduction, missingCheckpoint, consumeMs, stack, done);
    }

    private static void resolveCandidates(Ingredient ingredient, long count, ResolutionContext ctx,
                                          int depth, EdgeTracker edges, @Nullable InputPortId consumer,
                                          @Nullable List<ResolutionContext.SupplySlice> consumedOut,
                                          boolean prepareOnly, long remaining, int minReserve,
                                          @Nullable ResourceLocation forcedRecipeId, boolean forceProduction,
                                          int missingCheckpoint, long consumeMs,
                                          PlanningStack stack, Consumer<Boolean> done) {
        if (!forceProduction && minReserve > 0) {
            remaining += minReserve;
        }

        if (remaining <= 0) {
            ResolutionContext.SupplyConsumption existing =
                    ctx.consumeMatchingDetailed(ingredient, prepareOnly ? 0L : count);
            if (existing.complete()) recordSupplyConsumption(ctx, consumer, consumedOut, existing);
            if (existing.complete()) ctx.truncateMissing(missingCheckpoint);
            stack.complete(done, existing.complete());
            return;
        }
        if (ctx.isNoRecipe(ingredient)) {
            ctx.beginUndo();
            edges.beginUndo();
            ResolutionContext.SupplyConsumption stocked =
                    ctx.consumeMatchingDetailed(ingredient, prepareOnly ? 0L : count);
            recordSupplyConsumption(ctx, consumer, consumedOut, stocked);
            ctx.commitUndo();
            edges.commitUndo();
            if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient stop: no_recipe for "
                    + describeFirstItem(ingredient) + " missing="
                    + Math.max(0L, count - stocked.supplied()));
            finishMissing(ctx, consumer, ingredient, Math.max(0L, count - stocked.supplied()),
                    "no-recipe", missingCheckpoint, stack, done);
            return;
        }

        long candStart = System.nanoTime();
        // 调试日志开启时顺手记下被跳过的候选与原因：玩家报告「计划树里有的配方执行时不见了」，
        // 现有日志只能看到剩下几个候选，看不到是谁按什么规则被滤掉的。
        List<CandidateEngine.CandidateDiagnostic> candidateDiag =
                RSIntegrationMod.isDebugLoggingEnabled() ? new ArrayList<>() : null;
        long needed = remaining;
        CandidateEngine.findCandidates(ingredient, ctx, candidateDiag, needed, stack, candidates ->
                collectViableCandidates(ingredient, count, ctx, depth, edges, consumer, consumedOut, prepareOnly,
                        needed, forcedRecipeId, forceProduction, missingCheckpoint, consumeMs,
                        candStart, candidateDiag, candidates, stack, done));
    }

    private record AliveCandidate(RecipeIndex.Entry entry, ItemStack output, int netGain, int selfConsumed) {}

    private static void collectViableCandidates(Ingredient ingredient, long count, ResolutionContext ctx,
                                               int depth, EdgeTracker edges, @Nullable InputPortId consumer,
                                               @Nullable List<ResolutionContext.SupplySlice> consumedOut,
                                               boolean prepareOnly, long remaining,
                                               @Nullable ResourceLocation forcedRecipeId, boolean forceProduction,
                                               int missingCheckpoint, long consumeMs, long candStart,
                                               @Nullable List<CandidateEngine.CandidateDiagnostic> candidateDiag,
                                               List<RecipeIndex.Entry> candidates,
                                               PlanningStack stack, Consumer<Boolean> done) {
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
            finishMissing(ctx, consumer, ingredient, count, "no-candidates", missingCheckpoint, stack, done);
            return;
        }

        List<AliveCandidate> alive = new ArrayList<>();
        long aliveStart = System.nanoTime();
        int candidateCount = candidates.size();
        var entries = candidates.iterator();
        class Outputs implements Runnable {
            @Override
            public void run() {
                if (!entries.hasNext() || ctx.timedOut()) {
                    if (entries.hasNext()) PerformanceMonitor.recordResolveTimeout();
                    tryViableCandidates(ingredient, count, ctx, depth, edges, consumer, consumedOut,
                            prepareOnly, remaining, forcedRecipeId, forceProduction, missingCheckpoint,
                            candidateDiag, candidateCount, aliveStart, alive, stack, done);
                    return;
                }
                stack.resume(this);
                addIfViable(entries.next());
            }

            private void addIfViable(RecipeIndex.Entry candidate) {
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
                return;
            }
            int selfConsumed = selfConsumedPerBatch(candidate, out, ctx.level.registryAccess(), ctx);
            int ng = netGain(declaredOutputCount(ctx.declaredRecipeOutputs(candidate.recipe()), out), selfConsumed);
            if (ng <= 0) {
                if (ctx.diagnosticsEnabled()) {
                    ctx.diag("ensureIngredient REJECT " + candidate.recipe().getId()
                            + ": non-positive net gain output=" + out.getCount()
                            + " selfConsumed=" + selfConsumed);
                }
                return;
            }
            alive.add(new AliveCandidate(candidate, out, ng, selfConsumed));
            }
        }
        stack.resume(new Outputs());
    }

    private static void tryViableCandidates(Ingredient ingredient, long count, ResolutionContext ctx,
                                           int depth, EdgeTracker edges, @Nullable InputPortId consumer,
                                           @Nullable List<ResolutionContext.SupplySlice> consumedOut,
                                           boolean prepareOnly, long remaining,
                                           @Nullable ResourceLocation forcedRecipeId, boolean forceProduction,
                                           int missingCheckpoint,
                                           @Nullable List<CandidateEngine.CandidateDiagnostic> candidateDiag,
                                           int candidateCount, long aliveStart, List<AliveCandidate> alive,
                                           PlanningStack stack, Consumer<Boolean> done) {
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
            if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient FAILED: no viable candidates (total=" + candidateCount
                    + ", all rejected) for " + describeFirstItem(ingredient));
            if (RSIntegrationMod.isDebugLoggingEnabled()) {
                RSIntegrationMod.debug("[RSI-ensure] no viable candidates for {} ({} total candidates rejected, alive-building took {}ms)",
                        describeFirstItem(ingredient), candidateCount, aliveMs);
            }
            logSkippedCandidates("no-viable-candidates", ingredient, candidateDiag);
            finishMissing(ctx, consumer, ingredient, count, "no-viable-candidates", missingCheckpoint, stack, done);
            return;
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

        List<AliveCandidate> viable = alive;
        long needed = remaining;
        boolean ownsTwoPhase = ctx.bestEffort
                && !ctx.strictCandidatePass && !ctx.bestEffortFallbackPass;
        int candidatePasses = ownsTwoPhase ? 2 : 1;
        boolean previousStrict = ctx.strictCandidatePass;
        boolean previousFallback = ctx.bestEffortFallbackPass;
        stack.enter(() -> {
            ctx.strictCandidatePass = previousStrict;
            ctx.bestEffortFallbackPass = previousFallback;
        });
        class Candidates implements Runnable {
            private int pass;
            private int position;

            @Override
            public void run() {
                if (pass < candidatePasses) {
                    if (position == 0 && ownsTwoPhase) {
                        ctx.strictCandidatePass = pass == 0;
                        ctx.bestEffortFallbackPass = pass == 1;
                        if (ctx.diagnosticsEnabled()) ctx.diag(pass == 0
                                ? "ensureIngredient candidate pass=strict"
                                : "ensureIngredient candidate pass=best-effort-fallback");
                    }
                    if (position < viable.size()) {
                        if (!tryCandidate(viable.get(position++))) stack.resume(this);
                        return;
                    }
                    position = 0;
                    pass++;
                    stack.resume(this);
                    return;
                }
                stack.leave();
                if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient FAILED: exhausted " + viable.size()
                        + " viable candidates for " + describeFirstItem(ingredient));
                finishMissing(ctx, consumer, ingredient, count, "candidates-exhausted", missingCheckpoint, stack, done);
            }

            private boolean tryCandidate(AliveCandidate a) {
            // Don't check timedOut() before the first candidate — the alive-building
            // loop may have exhausted the deadline with expensive result-item probes
            // for mod recipes. The first candidate is the highest-scored and is near-
            // certain to be a vanilla recipe with fast craftBatched.
            if (ctx.recipeCycles.containsBranch(a.entry.recipe().getId(), a.output)) {
                if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": cycle detected");
                return false;
            }

            StackKey outKey = StackKey.of(a.output, a.output.hasTag());
            if (ctx.recipeCycles.containsOutput(a.output)) {
                if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId()
                        + ": ancestor output cycle detected");
                return false;
            }

            if (requiresMissingSelfInput(a.entry, a.output, ctx)) {
                if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": self-input missing");
                return false;
            }

            // Ping-pong guard using StackKey (Item + NBT) to correctly distinguish
            // NBT-differentiated items like TACZ attachments (all tacz:attachment).
            Set<StackKey> inKeys = ctx.candidateCycleInputs.computeIfAbsent(a.entry,
                    candidate -> getRecipeInputKeys(candidate, ctx));
            if (ctx.recipeCycles.containsAnyOutput(inKeys)) {
                if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId()
                        + ": input consumes an ancestor output");
                return false;
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
                return false;
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
                    SaturatingLongMath.ceilDiv(needed, a.netGain));
            int selfConsumed = a.selfConsumed();

            ctx.recordPreviewCandidate(a.entry);
            ctx.beginUndo();
            edges.beginUndo();
            ctx.recipeCycles.enter(a.entry.recipe().getId(), a.output);
            Set<Set<Item>> conversionFamilies = a.entry.recipe() instanceof CraftingRecipe cr
                    ? NonProductiveTagConversionGuard.conversionFamilies(cr, a.output)
                    : Set.of();
            for (Set<Item> family : conversionFamilies) ctx.pushConversionFamily(family);

            boolean activatedForcedRecipe = forceProduction
                    && ctx.activateForcedRecipe(a.entry.recipe().getId());
            stack.enter(() -> {
                if (activatedForcedRecipe) ctx.deactivateForcedRecipe(a.entry.recipe().getId());
                ctx.recipeCycles.leave(a.entry.recipe().getId(), a.output);
                for (Set<Item> family : conversionFamilies) ctx.popConversionFamily(family);
                // 转换边只属于正在展开的路径。相邻分支可使用独立库存做合法的反向转换。
                edges.rollback();
            });
                for (StackKey inKey : inKeys) {
                    edges.addEdge(inKey, outKey);
                }
                class Batches implements Runnable {
                    private long batchesLeft = batches;

                    @Override
                    public void run() {
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
                            finishCandidate(a, false);
                            return;
                        }
                    }
                    batchesLeft -= stageBatches;
                    StepExecutor.craftBatched(a.entry, ctx, depth, altIds, altModTypes, edges, stageBatches,
                            a.output, stack, ok -> {
                                if (ok && batchesLeft > 0) stack.resume(this);
                                else finishCandidate(a, ok);
                            });
                    }
                }
                stack.resume(new Batches());
                return true;
            }

            private void finishCandidate(AliveCandidate a, boolean allOk) {
            stack.leave();
            if (!allOk) {
                if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": craftOnce failed");
                ctx.rollback();
                retryOrTimeout("timeout-after-craft-failure");
                return;
            }

            ResolutionContext.SupplyConsumption crafted = forceProduction && !prepareOnly
                    ? ctx.consumeProducedMatchingDetailed(ingredient, count)
                    : ctx.consumeMatchingDetailed(ingredient, prepareOnly ? 0L : count);
            if (crafted.complete() && (!prepareOnly || ctx.countMatching(ingredient) >= count)) {
                recordSupplyConsumption(ctx, consumer, consumedOut, crafted);
                if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient OK " + a.entry.recipe().getId());
                ctx.truncateMissing(missingCheckpoint);
                ctx.commitUndo();
                stack.leave();
                stack.complete(done, true);
                return;
            }

            if (ctx.diagnosticsEnabled()) ctx.diag("ensureIngredient SKIP " + a.entry.recipe().getId() + ": consumeMatching failed after craft");
            ctx.rollback();
            retryOrTimeout("timeout-after-consume-failure");
            }

            private void retryOrTimeout(String reason) {
                if (!ctx.timedOut()) {
                    stack.resume(this);
                    return;
                }
                PerformanceMonitor.recordResolveTimeout();
                boolean resolved = recordBestEffortUnresolved(ctx, consumer, ingredient, count, reason);
                if (!resolved) recordFailureMissing(ctx, ingredient, missingCheckpoint);
                stack.leave();
                stack.complete(done, resolved);
            }
        }
        stack.resume(new Candidates());
    }

    private static void finishMissing(ResolutionContext ctx, @Nullable InputPortId consumer,
                                      Ingredient ingredient, long count, String reason, int missingCheckpoint,
                                      PlanningStack stack, Consumer<Boolean> done) {
        boolean resolved = recordBestEffortUnresolved(ctx, consumer, ingredient, count, reason);
        if (!resolved) recordFailureMissing(ctx, ingredient, missingCheckpoint);
        stack.complete(done, resolved);
    }

    /**
     * 后续槽位也需要同一种复制材料时，先补齐合计需求，再交给当前分支消费。
     * 探测失败则回滚，避免预先备料阻断本来可行的生产顺序或污染缺料提示。
     */
    private static void prepareSharedAmplification(Ingredient ingredient, long count,
                                                   ResolutionContext ctx, int depth,
                                                   EdgeTracker edges, PlanningStack stack, Runnable next) {
        if (count <= 0L || ctx.pendingInputs.isEmpty() || ctx.timedOut()
                || ctx.forcedRecipeFor(ingredient) != null || ctx.isNoRecipe(ingredient)) {
            stack.resume(next);
            return;
        }
        ItemStack[] options = ingredient.getItems();
        if (options.length != 1 || options[0].isEmpty()) {
            stack.resume(next);
            return;
        }
        ItemStack output = options[0];
        var entries = ctx.index.get(output.getItem());
        if (entries == null || entries.isEmpty()) {
            stack.resume(next);
            return;
        }
        long availableSeed = ctx.countMatching(ingredient);
        if (availableSeed <= 0L || ctx.recipeCycles.containsOutput(output)) {
            stack.resume(next);
            return;
        }
        long pending = ctx.pendingConsumption(output);
        long target = SaturatingLongMath.add(count, pending);
        if (pending <= 0L || availableSeed >= target) {
            stack.resume(next);
            return;
        }
        var candidates = entries.iterator();
        // 复制预检也可能调用第三方配方，逐个推进才能在候选之间让出 tick。
        class SeedProbe implements Runnable {
            @Override
            public void run() {
                if (!candidates.hasNext() || ctx.timedOut()) {
                    stack.resume(next);
                    return;
                }
                RecipeIndex.Entry entry = candidates.next();
                ItemStack declared = CandidateEngine.outputForDemand(entry, ingredient, ctx);
                if (!declared.isEmpty()) {
                    RegistryAccess access = ctx.level.registryAccess();
                    int seed = selfConsumedPerBatch(entry, declared, access, ctx);
                    if (seed > 0 && availableSeed >= seed
                            && declaredOutputCount(ctx.declaredRecipeOutputs(entry.recipe()), declared) > seed) {
                        resolveSharedAmplification(ingredient, ctx, depth, edges, stack, next, target);
                        return;
                    }
                }
                stack.resume(this);
            }
        }
        stack.resume(new SeedProbe());
    }

    private static void resolveSharedAmplification(Ingredient ingredient, ResolutionContext ctx,
                                                   int depth, EdgeTracker edges, PlanningStack stack,
                                                   Runnable next, long target) {
        int missingCheckpoint = ctx.missingCount();
        boolean previousStrict = ctx.strictCandidatePass;
        ctx.beginUndo();
        edges.beginUndo();
        ctx.strictCandidatePass = true;
        stack.enter(() -> {
            ctx.strictCandidatePass = previousStrict;
            ctx.truncateMissing(missingCheckpoint);
        });
        stack.resume(() -> resolveIngredient(ingredient, target, ctx, depth, edges, null, null, true, stack, resolved -> {
            if (resolved) {
                ctx.commitUndo();
                edges.commitUndo();
            } else {
                ctx.rollback();
                edges.rollback();
            }
            stack.leave();
            stack.resume(next);
        }));
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

    private static Set<StackKey> getRecipeInputKeys(RecipeIndex.Entry entry, ResolutionContext ctx) {
        RegistryAccess access = ctx.level.registryAccess();
        if (entry.recipe() instanceof CraftingRecipe) {
            return getRecipeInputKeys(entry.recipe(), access);
        }
        List<IngredientSpec> specs = recipeSpecs(entry, access, ctx);
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
        List<IngredientSpec> specs = recipeSpecs(entry, ctx.level.registryAccess(), ctx);
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
        return netGain(produced, selfConsumedPerBatch(entry, output, access, null));
    }

    private static int netGain(long produced, long consumed) {
        long net = produced - consumed;
        return net <= 0L ? 0 : SaturatingLongMath.clampToInt(net);
    }

    static long declaredOutputCount(RecipeIndex.Entry entry, ItemStack output,
                                    RegistryAccess access) {
        ModRecipeHandler handler = ModRecipeHandlers.claimFor(entry.recipe()).winner();
        return handler == null ? output.getCount()
                : declaredOutputCount(handler.getDeclaredOutputs(entry.recipe(), access), output);
    }

    static long declaredOutputCount(List<RecipeOutputSpec> declarations, ItemStack output) {
        long total = 0L;
        for (RecipeOutputSpec declaration : declarations) {
            MaterialKey material = declaration.material();
            if (material == null
                    || !MaterialMatcher.equivalentRuntimeFragment(material.toStack(1), output)) continue;
            long credited = declaration.probability().isGuaranteed()
                    ? declaration.minimumPerOperation()
                    : declaration.expectedPerOperation();
            total = SaturatingLongMath.add(total, credited);
        }
        return total > 0L ? total : output.getCount();
    }

    private static int selfConsumedPerBatch(RecipeIndex.Entry entry, ItemStack output,
                                             RegistryAccess access, @Nullable ResolutionContext ctx) {
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
        List<IngredientSpec> specs = recipeSpecs(entry, access, ctx);
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
        // 先检查真实材料谓词，避免把共用 Item 的不同流体或 NBT 状态误判为自消耗。
        if (!IngredientMatcher.test(ingredient, output)) return false;
        if (!IngredientMatcher.hasCompleteItemList(ingredient)) {
            return true;
        }
        int candidates = 0;
        for (ItemStack template : ingredient.getItems()) {
            if (template.isEmpty()) continue;
            candidates++;
            if (template.getItem() != output.getItem()) return false;
        }
        return candidates > 0;
    }

    private static List<IngredientSpec> recipeSpecs(RecipeIndex.Entry entry, @Nullable RegistryAccess access,
                                                     @Nullable ResolutionContext ctx) {
        List<IngredientSpec> specs = ctx == null
                ? CraftPacketUtils.extractSingleOperationRecursiveIngredientSpecs(entry.recipe())
                : ctx.declaredRecursiveInputs(entry);
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

        public static StackKey of(ItemStack stack, boolean includeNbt) {
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
        private record ConversionEdge(StackKey input, StackKey output) {}

        private final Set<ConversionEdge> activeEdges = new HashSet<>();
        private final List<ConversionEdge> undo = new ArrayList<>();
        private final Deque<Integer> checkpoints = new ArrayDeque<>();

        public void beginUndo() {
            checkpoints.push(undo.size());
        }

        public void commitUndo() {
            if (checkpoints.isEmpty()) return;
            checkpoints.pop();
            // 已提交的边留在主集合中；提交子分支不再逐层复制整段变更记录。
            if (checkpoints.isEmpty()) undo.clear();
        }

        public void rollback() {
            if (checkpoints.isEmpty()) return;
            int checkpoint = checkpoints.pop();
            while (undo.size() > checkpoint) activeEdges.remove(undo.remove(undo.size() - 1));
        }

        public void addEdge(StackKey in, StackKey out) {
            // 自增配方的种子输入不是反向换算；净收益与种子数量由解析器另行校验。
            if (in.equals(out)) return;
            var edge = new ConversionEdge(in, out);
            if (activeEdges.add(edge) && !checkpoints.isEmpty()) undo.add(edge);
        }

        public boolean containsReverse(StackKey in, StackKey out) {
            return activeEdges.contains(new ConversionEdge(out, in));
        }
    }
}
