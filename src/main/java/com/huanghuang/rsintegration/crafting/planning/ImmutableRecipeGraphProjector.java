package com.huanghuang.rsintegration.crafting.planning;

import com.google.gson.JsonElement;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable pure-planning projection published with the matching recipe index generation. */
public final class ImmutableRecipeGraphProjector {
    private static volatile CachedProjection cachedProjection;

    private ImmutableRecipeGraphProjector() {}

    public static boolean isReady(Level level) {
        CachedProjection ready = cachedProjection;
        return ready != null && ready.matches(
                level.getRecipeManager(), CraftPlanningRevision.current());
    }

    public static ImmutableRecipeGraph capture(Level level) {
        PlanningThreadContext.requireMainThread("recipe graph projection");
        CachedProjection ready = cachedProjection;
        if (ready != null && ready.matches(
                level.getRecipeManager(), CraftPlanningRevision.current())) {
            PerformanceMonitor.recordRecipeGraphProjection(true, 0L);
            return ready.graph();
        }

        // Compatibility fallback for callers that invoke capture before the normal
        // startup/reload lifecycle. Planning packets never reach this path because
        // their readiness gate requires the complete generation.
        RecipeIndex.warmUpBlocking(level);
        ready = cachedProjection;
        if (ready == null || !ready.matches(
                level.getRecipeManager(), CraftPlanningRevision.current())) {
            throw new IllegalStateException("Recipe graph generation is unavailable");
        }
        return ready.graph();
    }

    /** Publishes a graph captured during the same pass that built the recipe index. */
    public static synchronized void publishCompiled(RecipeManager source, long revision,
                                                     ImmutableRecipeGraph graph,
                                                     long buildNanos) {
        cachedProjection = new CachedProjection(source, revision, graph);
        PerformanceMonitor.recordRecipeGraphProjection(false, buildNanos);
    }

    /** Captures one ordinary crafting recipe without invoking generic reflective extraction. */
    @Nullable
    public static RecipeNode projectCraftingRecipe(CraftingRecipe recipe, ItemStack output) {
        return projectCraftingRecipe(recipe, output,
                CraftPacketUtils.extractCraftingIngredientSpecs(recipe));
    }

    @Nullable
    public static RecipeNode projectCraftingRecipe(CraftingRecipe recipe, ItemStack output,
                                                    List<IngredientSpec> specs) {
        return projectRecipe(recipe.getId(), output, specs, "generic",
                new ResourceLocation("minecraft", "crafting"));
    }

    @Nullable
    public static RecipeNode projectRecipe(ResourceLocation recipeId, ItemStack output,
                                           List<IngredientSpec> specs, String modTypeId,
                                           ResourceLocation recipeTypeId) {
        return projectRecipe(recipeId, output, specs, modTypeId, recipeTypeId, true);
    }

    @Nullable
    public static RecipeNode projectRecipe(ResourceLocation recipeId, ItemStack output,
                                           List<IngredientSpec> specs, String modTypeId,
                                           ResourceLocation recipeTypeId,
                                           boolean includeOutputNbt) {
        if (output.isEmpty()) return null;
        List<IngredientRef> inputs = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            IngredientRef input = projectIngredient(spec);
            if (input == null) return null;
            inputs.add(input);
        }
        MaterialRef outputRef = material(output, includeOutputNbt && output.hasTag());
        return new RecipeNode(recipeId, outputRef, Math.max(1, output.getCount()),
                inputs, modTypeId, recipeTypeId);
    }

    public static synchronized void clearCache() {
        cachedProjection = null;
    }

    public static Map<MaterialRef, Integer> projectAvailability(Map<StackKey, Integer> available) {
        Map<MaterialRef, Integer> projected = new java.util.HashMap<>();
        for (Map.Entry<StackKey, Integer> entry : available.entrySet()) {
            PlanningThreadContext.throwIfCancelled();
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(entry.getKey().item());
            if (itemId == null || entry.getValue() <= 0) continue;
            String nbt = entry.getKey().tag() == null ? "" : entry.getKey().tag();
            projected.merge(new MaterialRef(itemId, nbt), entry.getValue(), Integer::sum);
        }
        return Map.copyOf(projected);
    }

    /**
     * Bind value-only ingredient predicates to the concrete inventory variants in this snapshot.
     * Stock is kept one-to-one with physical item/NBT identities, so a tagged stack cannot be
     * counted once as an exact ingredient and again through a tagless alias.
     */
    public static ImmutableRecipeGraph bindAvailability(
            ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available) {
        Map<ResourceLocation, List<MaterialRef>> availableByItem = new java.util.HashMap<>();
        for (Map.Entry<MaterialRef, Integer> entry : available.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            availableByItem.computeIfAbsent(entry.getKey().itemId(), ignored -> new ArrayList<>())
                    .add(entry.getKey());
        }
        Map<IngredientRef, IngredientRef> boundIngredients = new java.util.HashMap<>();
        Map<MaterialRef, List<RecipeNode>> projected = new java.util.LinkedHashMap<>();
        for (Map.Entry<MaterialRef, List<RecipeNode>> entry : graph.recipesByOutput().entrySet()) {
            List<RecipeNode> recipes = new ArrayList<>(entry.getValue().size());
            for (RecipeNode recipe : entry.getValue()) {
                List<IngredientRef> inputs = recipe.inputs().stream()
                        .map(input -> boundIngredients.computeIfAbsent(input,
                                key -> bindIngredientIndexed(key, availableByItem)))
                        .toList();
                recipes.add(new RecipeNode(recipe.recipeId(), recipe.output(),
                        recipe.outputCount(), inputs, recipe.modTypeId(),
                        recipe.recipeTypeId()));
            }
            projected.put(entry.getKey(), recipes);
        }
        return new ImmutableRecipeGraph(projected);
    }

    static IngredientRef bindIngredient(IngredientRef ingredient,
                                        Map<MaterialRef, Integer> available) {
        Map<ResourceLocation, List<MaterialRef>> availableByItem = new java.util.HashMap<>();
        for (Map.Entry<MaterialRef, Integer> entry : available.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            availableByItem.computeIfAbsent(entry.getKey().itemId(), ignored -> new ArrayList<>())
                    .add(entry.getKey());
        }
        return bindIngredientIndexed(ingredient, availableByItem);
    }

    private static IngredientRef bindIngredientIndexed(
            IngredientRef ingredient,
            Map<ResourceLocation, List<MaterialRef>> availableByItem) {
        if (ingredient.nbtMatchMode() == NbtMatchMode.EXACT) return ingredient;
        Set<MaterialRef> alternatives = new LinkedHashSet<>(ingredient.alternatives());
        for (MaterialRef expected : ingredient.alternatives()) {
            for (MaterialRef actual : availableByItem.getOrDefault(
                    expected.itemId(), List.of())) {
                if (ingredient.nbtMatchMode() == NbtMatchMode.ANY
                        || partialNbtMatches(expected.nbt(), actual.nbt())) {
                    alternatives.add(actual);
                }
            }
        }
        return new IngredientRef(List.copyOf(alternatives), ingredient.count(),
                ingredient.nbtMatchMode());
    }

    static boolean partialNbtMatches(String expectedSnbt, String actualSnbt) {
        try {
            CompoundTag expected = expectedSnbt == null || expectedSnbt.isBlank()
                    ? null : TagParser.parseTag(expectedSnbt);
            CompoundTag actual = actualSnbt == null || actualSnbt.isBlank()
                    ? null : TagParser.parseTag(actualSnbt);
            return expected == null || actual != null && NbtUtils.compareNbt(expected, actual, true);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static IngredientRef projectIngredient(IngredientSpec spec) {
        // Replacement containers still consume one full input per execution; their
        // remainder is emitted by the real crafting executor. Reusable or transformed
        // inputs have different demand scaling and cannot be flattened this way.
        if (spec.role() != DemandRole.CONSUMED
                && spec.role() != DemandRole.CONTAINER_RETURNING) return null;
        Ingredient ingredient = spec.ingredient();
        boolean strictNbt = IngredientMatcher.requiresNbt(ingredient);
        NbtMatchMode matchMode = nbtMatchMode(ingredient);
        Set<MaterialRef> alternatives = new LinkedHashSet<>();
        for (ItemStack candidate : ingredient.getItems()) {
            if (!candidate.isEmpty()) alternatives.add(material(candidate, strictNbt));
        }
        return alternatives.isEmpty() ? null
                : new IngredientRef(List.copyOf(alternatives), spec.count(), matchMode);
    }

    private static boolean isPartialNbtIngredient(Ingredient ingredient) {
        for (Class<?> type = ingredient.getClass(); type != null; type = type.getSuperclass()) {
            if (isPartialNbtIngredientClass(type.getName())) return true;
        }
        try {
            return containsPartialTagType(ingredient.toJson());
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Determines the immutable planner's NBT semantics for one live ingredient. */
    public static NbtMatchMode nbtMatchMode(Ingredient ingredient) {
        if (!IngredientMatcher.requiresNbt(ingredient)) return NbtMatchMode.ANY;
        return isPartialNbtIngredient(ingredient) ? NbtMatchMode.PARTIAL : NbtMatchMode.EXACT;
    }

    static boolean containsPartialTagType(JsonElement json) {
        if (json == null || json.isJsonNull()) return false;
        if (json.isJsonPrimitive()) {
            return json.getAsJsonPrimitive().isString()
                    && "crafttweaker:partial_tag".equals(json.getAsString());
        }
        if (json.isJsonArray()) {
            for (JsonElement child : json.getAsJsonArray()) {
                if (containsPartialTagType(child)) return true;
            }
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
            if (containsPartialTagType(entry.getValue())) return true;
        }
        return false;
    }

    static boolean isPartialNbtIngredientClass(String className) {
        return "com.blamejared.crafttweaker.api.ingredient.type.IngredientPartialTag"
                .equals(className);
    }

    public static MaterialRef material(ItemStack stack, boolean includeNbt) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) throw new IllegalArgumentException("Unregistered item in planning snapshot");
        return new MaterialRef(id,
                includeNbt && stack.getTag() != null ? stack.getTag().toString() : "");
    }

    private record CachedProjection(RecipeManager source, long revision,
                                    ImmutableRecipeGraph graph) {
        private boolean matches(RecipeManager currentSource, long currentRevision) {
            return source == currentSource && revision == currentRevision;
        }
    }
}
