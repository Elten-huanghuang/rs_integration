package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.recipe.MaterialRequirement;
import com.huanghuang.rsintegration.api.recipe.OutputDeclaration;
import com.huanghuang.rsintegration.api.recipe.OutputProbabilityMode;
import com.huanghuang.rsintegration.api.recipe.RecipeAvailability;
import com.huanghuang.rsintegration.api.recipe.RecipeAvailabilityContext;
import com.huanghuang.rsintegration.api.recipe.RecipeAvailabilityProvider;
import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeProjectionThread;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.storage.StorageCapability;
import com.huanghuang.rsintegration.storage.StorageFluidKey;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.RecipeOutputSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 将单个公共 RecipeIntegration 接入内部配方处理器链。
 *
 * <p>公共注册优先级直接成为内部 claim 优先级，因此第三方配方与官方
 * 处理器冲突时仍由 {@code ModRecipeHandlers.claimFor} 给出唯一 winner
 * 或显式歧义。</p>
 */
final class ExternalRecipeHandler implements ModRecipeHandler {
    private final RecipeIntegration<?> integration;
    private final ModType modType;
    private final int priority;
    private final Predicate<Recipe<?>> claimed;
    private final Map<ProjectionKey, ExternalRecipeProjection.Result> projections =
            new ConcurrentHashMap<>();

    ExternalRecipeHandler(
            RecipeIntegration<?> integration,
            ModType modType,
            int priority,
            Predicate<Recipe<?>> claimed
    ) {
        this.integration = Objects.requireNonNull(integration, "integration");
        this.modType = Objects.requireNonNull(modType, "modType");
        this.priority = priority;
        this.claimed = Objects.requireNonNull(claimed, "claimed");
    }

    @Override
    public ModType modType() {
        return modType;
    }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return claimed.test(recipe);
    }

    @Override
    public int claimPriority() {
        return priority;
    }

    @Override
    public boolean cacheByRecipeClass() {
        return false;
    }

    @Override
    public boolean preferHandlerIngredients() {
        return true;
    }

    @Override
    public boolean isAvailableForPlanning(
            Recipe<?> recipe,
            @Nullable ServerPlayer player
    ) {
        ExternalRecipeProjection.Result projection = project(recipe);
        if (!projection.available()) return false;
        if (!projection.view().fluids().isEmpty()) {
            if (player == null) return false;
            var endpoint = CraftStorageEndpoints.resolveDefault(player)
                    .orElse(null);
            if (endpoint == null
                    || !endpoint.session().supports(
                    StorageCapability.FLUID_STORAGE)) {
                return false;
            }
            var snapshotResult = endpoint.session().snapshotFluids(player);
            if (!snapshotResult.successful()) return false;
            var snapshot = snapshotResult.snapshotValue().orElseThrow();
            Map<StorageFluidKey, Long> reserved = new LinkedHashMap<>();
            for (var fluid : projection.view().fluids()) {
                StorageFluidKey key = ExternalFluidMatcher.find(
                        fluid, snapshot, reserved);
                if (key == null) return false;
                ExternalFluidMatcher.reserve(
                        reserved, key, fluid.amount());
            }
        }
        RecipeAvailabilityProvider provider =
                integration.availabilityProvider().orElse(null);
        if (provider == null) return true;
        if (player == null) return false;
        try {
            RecipeAvailability availability = provider.check(
                    new RecipeAvailabilityContext(
                            recipeContext(player.serverLevel().registryAccess(),
                                    projection.revision()),
                            recipe,
                            projection.view(),
                            player,
                            null,
                            null
                    )
            );
            return availability.planningAvailable();
        } catch (RuntimeException | LinkageError exception) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-API] availability check failed for recipe {} via {}",
                    recipe.getId(), integration.id(), exception);
            return false;
        }
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        ExternalRecipeProjection.Result projection = project(recipe, access);
        return projection.available()
                ? ExternalRecipeProjection.primaryStack(projection.view())
                : ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        ExternalRecipeProjection.Result projection = project(recipe);
        if (!projection.available()) return null;
        try {
            return ExternalRecipeProjection.ingredients(projection.view());
        } catch (IllegalArgumentException exception) {
            logProjectionFailure(recipe, exception);
            return null;
        }
    }

    @Override
    public List<ScaledIngredientSpec> getRecursiveScaledIngredients(
            Recipe<?> recipe,
            @Nullable List<ScaledIngredientSpec> ingredients
    ) {
        if (ingredients == null || ingredients.isEmpty()) return List.of();
        ExternalRecipeProjection.Result projection = project(recipe);
        if (!projection.available()
                || !projection.view().flags().recursivePlanning()) {
            return List.of();
        }
        List<ScaledIngredientSpec> result = new ArrayList<>();
        List<MaterialRequirement> requirements = projection.view().materials();
        for (int index = 0;
             index < Math.min(ingredients.size(), requirements.size());
             index++) {
            if (!requirements.get(index).recursive()) continue;
            ScaledIngredientSpec spec = ingredients.get(index);
            result.add(spec);
        }
        return List.copyOf(result);
    }

    @Override
    public List<ItemStack> getSecondaryOutputs(
            Recipe<?> recipe,
            RegistryAccess access
    ) {
        ExternalRecipeProjection.Result projection = project(recipe, access);
        if (!projection.available()) return List.of();
        try {
            return ExternalRecipeProjection.exactSecondaryOutputs(projection.view());
        } catch (IllegalArgumentException exception) {
            logProjectionFailure(recipe, exception);
            return List.of();
        }
    }

    @Override
    public boolean hasDeterministicPrimaryOutput(Recipe<?> recipe) {
        ExternalRecipeProjection.Result projection = project(recipe);
        return projection.available()
                && ExternalRecipeProjection.deterministicPrimary(projection.view());
    }

    @Override
    public boolean supportsTargetedProduction(Recipe<?> recipe) {
        ExternalRecipeProjection.Result projection = project(recipe);
        return projection.available()
                && ExternalRecipeProjection.targetProducing(projection.view());
    }

    @Override
    public boolean hasRuntimeDependentPrimaryNbt(Recipe<?> recipe) {
        ExternalRecipeProjection.Result projection = project(recipe);
        return projection.available()
                && ExternalRecipeProjection.runtimeDependentPrimary(projection.view());
    }

    @Override
    public List<RecipeOutputSpec> getDeclaredOutputs(
            Recipe<?> recipe,
            RegistryAccess access
    ) {
        ExternalRecipeProjection.Result projection = project(recipe, access);
        if (!projection.available()) return List.of();
        Map<ResourceLocation, Integer> inputIndexes = new LinkedHashMap<>();
        for (int index = 0;
             index < projection.view().materials().size();
             index++) {
            inputIndexes.put(
                    projection.view().materials().get(index).entryId(), index);
        }
        List<RecipeOutputSpec> outputs = new ArrayList<>();
        for (OutputDeclaration output : projection.view().outputs()) {
            if (output.resource().kind()
                    != IntegrationResourceKey.ResourceKind.ITEM) {
                continue;
            }
            int minimum = (int) Math.min(
                    Integer.MAX_VALUE, output.amount().min());
            int maximum = (int) Math.min(
                    Integer.MAX_VALUE, output.amount().max());
            boolean probabilistic =
                    output.probability().mode()
                            != OutputProbabilityMode.GUARANTEED;
            int expected = probabilistic ? 0 : minimum;
            Integer sourceInput = output.sourceInputEntryId() == null
                    ? null
                    : inputIndexes.get(output.sourceInputEntryId());
            outputs.add(new RecipeOutputSpec(
                    MaterialKey.of(ExternalRecipeProjection.toStack(
                            output.resource(), Math.max(1, maximum))),
                    expected,
                    probabilistic ? 0 : minimum,
                    maximum,
                    ExternalRecipeProjection.kind(output),
                    ExternalRecipeProjection.probability(output),
                    output.dynamicIdentity(),
                    sourceInput
            ));
        }
        return List.copyOf(outputs);
    }

    @Override
    public boolean hasStructuredOutputContract(Recipe<?> recipe) {
        return true;
    }

    @Override
    public boolean supportsBackgroundPlanning(Recipe<?> recipe) {
        return integration.projectionThread() == RecipeProjectionThread.BACKGROUND_SAFE;
    }

    @Override
    public boolean indexPrimaryOutput(Recipe<?> recipe) {
        ExternalRecipeProjection.Result projection = project(recipe);
        if (!projection.available()) return false;
        OutputDeclaration primary =
                ExternalRecipeProjection.primaryOutput(projection.view());
        return primary != null
                && primary.resource().kind()
                == IntegrationResourceKey.ResourceKind.ITEM;
    }

    private ExternalRecipeProjection.Result project(Recipe<?> recipe) {
        RegistryAccess registries = ServerLifecycleHooks.getCurrentServer() == null
                ? RegistryAccess.EMPTY
                : ServerLifecycleHooks.getCurrentServer().registryAccess();
        return project(recipe, registries);
    }

    private ExternalRecipeProjection.Result project(
            Recipe<?> recipe,
            RegistryAccess registries
    ) {
        long revision = CraftPlanningRevision.current();
        ProjectionKey key = new ProjectionKey(
                recipe.getId() == null
                        ? recipe.getClass().getName()
                        : recipe.getId().toString(),
                recipe.getClass(),
                revision
        );
        ExternalRecipeProjection.Result result = projections.get(key);
        if (result != null) return result;
        ExternalRecipeProjection.Result created =
                ExternalRecipeProjection.inspect(integration, recipe, registries);
        projections.keySet().removeIf(candidate ->
                candidate.recipeId().equals(key.recipeId())
                        && candidate.revision() != revision);
        projections.putIfAbsent(key, created);
        if (!created.available()) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-API] recipe {} was claimed by {} but could not be projected: {}",
                    recipe.getId(), integration.id(),
                    created.diagnostics().stream()
                            .map(IntegrationDiagnostic::code)
                            .toList());
        }
        return projections.getOrDefault(key, created);
    }

    private RecipeContext recipeContext(RegistryAccess registries, long revision) {
        return new RecipeContext(integration.id(), registries, revision);
    }

    private void logProjectionFailure(
            Recipe<?> recipe,
            IllegalArgumentException exception
    ) {
        RSIntegrationMod.LOGGER.warn(
                "[RSI-API] recipe {} projection from {} failed: {}",
                recipe.getId(), integration.id(), exception.getMessage());
    }

    private record ProjectionKey(
            String recipeId,
            Class<?> recipeClass,
            long revision
    ) {}
}
