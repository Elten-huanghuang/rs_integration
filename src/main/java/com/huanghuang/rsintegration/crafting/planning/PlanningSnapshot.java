package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.RegisteredMaterialSources.StockSnapshot;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Immutable, value-only input captured on the server thread for background planning. */
public record PlanningSnapshot(
        UUID playerId,
        long requestGeneration,
        long recipeRevision,
        ResourceLocation recipeId,
        Map<StackKey, Long> availableItems,
        Map<ResourceLocation, ResourceLocation> forcedRecipes,
        ImmutableRecipeGraph recipeGraph,
        String networkFingerprint,
        String bindingFingerprint,
        Set<ResourceLocation> bindingBlockedOutputIds,
        boolean mainThreadOnly,
        PlanningPreferences preferences,
        @Nullable BindingAnchor bindingAnchor,
        StockSnapshot extensionStock) {

    public PlanningSnapshot(UUID playerId, long requestGeneration, long recipeRevision,
                            ResourceLocation recipeId, Map<StackKey, Long> availableItems,
                            Map<ResourceLocation, ResourceLocation> forcedRecipes,
                            ImmutableRecipeGraph recipeGraph, String networkFingerprint,
                            String bindingFingerprint, Set<ResourceLocation> bindingBlockedOutputIds,
                            boolean mainThreadOnly, PlanningPreferences preferences, @Nullable BindingAnchor bindingAnchor) {
        this(playerId, requestGeneration, recipeRevision, recipeId, availableItems, forcedRecipes, recipeGraph,
                networkFingerprint, bindingFingerprint, bindingBlockedOutputIds, mainThreadOnly, preferences,
                bindingAnchor, StockSnapshot.EMPTY);
    }

    public PlanningSnapshot(UUID playerId, long requestGeneration, long recipeRevision,
                            ResourceLocation recipeId, Map<StackKey, Long> availableItems,
                            Map<ResourceLocation, ResourceLocation> forcedRecipes,
                            ImmutableRecipeGraph recipeGraph, String networkFingerprint,
                            String bindingFingerprint, Set<ResourceLocation> bindingBlockedOutputIds,
                            boolean mainThreadOnly, PlanningPreferences preferences) {
        this(playerId, requestGeneration, recipeRevision, recipeId, availableItems, forcedRecipes,
                recipeGraph, networkFingerprint, bindingFingerprint, bindingBlockedOutputIds,
                mainThreadOnly, preferences, null);
    }

    public PlanningSnapshot(UUID playerId, long requestGeneration, long recipeRevision,
                            ResourceLocation recipeId, Map<StackKey, Long> availableItems,
                            Map<ResourceLocation, ResourceLocation> forcedRecipes,
                            ImmutableRecipeGraph recipeGraph, String networkFingerprint,
                            String bindingFingerprint, boolean mainThreadOnly) {
        this(playerId, requestGeneration, recipeRevision, recipeId, availableItems,
                forcedRecipes, recipeGraph,
                networkFingerprint, bindingFingerprint,
                Set.of(), mainThreadOnly, PlanningPreferences.empty());
    }

    public PlanningSnapshot(UUID playerId, long requestGeneration, long recipeRevision,
                            ResourceLocation recipeId, Map<StackKey, Long> availableItems,
                            Map<ResourceLocation, ResourceLocation> forcedRecipes,
                            ImmutableRecipeGraph recipeGraph, String networkFingerprint,
                            String bindingFingerprint, Set<ResourceLocation> bindingBlockedOutputIds,
                            boolean mainThreadOnly) {
        this(playerId, requestGeneration, recipeRevision, recipeId, availableItems,
                forcedRecipes, recipeGraph, networkFingerprint, bindingFingerprint,
                bindingBlockedOutputIds, mainThreadOnly, PlanningPreferences.empty());
    }

    public PlanningSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(recipeId, "recipeId");
        availableItems = Map.copyOf(availableItems);
        forcedRecipes = Map.copyOf(forcedRecipes);
        recipeGraph = recipeGraph == null ? new ImmutableRecipeGraph(Map.of()) : recipeGraph;
        networkFingerprint = Objects.requireNonNullElse(networkFingerprint, "");
        bindingFingerprint = Objects.requireNonNullElse(bindingFingerprint, "");
        bindingBlockedOutputIds = bindingBlockedOutputIds == null
                ? Set.of() : Set.copyOf(bindingBlockedOutputIds);
        preferences = preferences == null ? PlanningPreferences.empty() : preferences;
        extensionStock = Objects.requireNonNull(extensionStock, "extensionStock");
    }

    public PlanningSnapshot withRecipeGraph(ImmutableRecipeGraph graph, Set<ResourceLocation> blockedOutputs) {
        return new PlanningSnapshot(playerId, requestGeneration, recipeRevision, recipeId,
                availableItems, forcedRecipes, graph, networkFingerprint, bindingFingerprint,
                blockedOutputs, mainThreadOnly, preferences, bindingAnchor, extensionStock);
    }

    public PlanningSnapshot withBindingAnchor(ResourceKey<Level> dimension, @Nullable BlockPos position) {
        return new PlanningSnapshot(playerId, requestGeneration, recipeRevision, recipeId, availableItems,
                forcedRecipes, recipeGraph, networkFingerprint, bindingFingerprint, bindingBlockedOutputIds,
                mainThreadOnly, preferences,
                new BindingAnchor(dimension, position == null ? null : position.immutable()), extensionStock);
    }

    public PlanningSnapshot withExtensionStock(StockSnapshot stock) {
        return new PlanningSnapshot(playerId, requestGeneration, recipeRevision, recipeId, availableItems,
                forcedRecipes, recipeGraph, networkFingerprint, bindingFingerprint, bindingBlockedOutputIds,
                mainThreadOnly, preferences, bindingAnchor, stock);
    }

    public boolean canSupplement(@Nullable PureRecipePlanner.Result result) {
        if (extensionStock.isEmpty() || result == null
                || result.feasibility() != PureRecipePlanner.Feasibility.INFEASIBLE
                || result.failureReason() != PureRecipePlanner.FailureReason.MISSING_INPUTS) return false;
        var offered = extensionStock.variants().stream()
                .map(key -> new ImmutableRecipeGraph.MaterialRef(BuiltInRegistries.ITEM.getKey(key.item()), key.tag()))
                .toList();
        Predicate<ImmutableRecipeGraph.IngredientRef> offeredFor = demand -> offered.stream()
                .anyMatch(material -> ImmutableRecipeGraphProjector.matchesIngredient(material, demand));
        if (result.missing().stream().anyMatch(offeredFor)) return true;

        // An offer for an unmet input can bypass the failed producer of that input.
        return Stream.concat(Stream.of(recipeId), result.steps().stream().map(PureRecipePlanner.PlannedStep::recipeId))
                .distinct().map(recipeGraph.recipesById()::get).filter(Objects::nonNull)
                .flatMap(recipe -> recipe.inputs().stream()).filter(offeredFor)
                .anyMatch(demand -> result.remaining().entrySet().stream()
                        .filter(entry -> ImmutableRecipeGraphProjector.matchesIngredient(entry.getKey(), demand))
                        .mapToLong(Map.Entry::getValue).reduce(0L, SaturatingLongMath::add) < demand.count());
    }

    /** 自动模式没有指定机器，不把玩家脚下方块当成某台机器。 */
    public record BindingAnchor(ResourceKey<Level> dimension, @Nullable BlockPos position) {}
}
