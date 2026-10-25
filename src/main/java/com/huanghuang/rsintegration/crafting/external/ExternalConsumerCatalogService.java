package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.ResourceMatchMode;
import com.huanghuang.rsintegration.api.consumer.ConsumerCapabilities;
import com.huanghuang.rsintegration.api.consumer.ConsumerDiagnostic;
import com.huanghuang.rsintegration.api.consumer.ConsumerDiagnosticCode;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeCandidate;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeCatalogPage;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeCatalogRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeQuery;
import com.huanghuang.rsintegration.api.consumer.ConsumerResult;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.recipe.RecipeExecutionKind;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.RecipeOutputSpec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 将冻结的配方索引投影为公共 Consumer 目录。
 *
 * <p>目录构建不读取库存、机器、方块实体或 availability callback。</p>
 */
@ApiStatus.Internal
public final class ExternalConsumerCatalogService {
    private static final int MAX_CURSOR_STATES = 2048;
    private volatile Snapshot snapshot;
    private final CursorRegistry cursors = new CursorRegistry(MAX_CURSOR_STATES);

    public ConsumerResult<ConsumerRecipeCatalogPage> findRecipes(
            ServerPlayer player,
            ConsumerRecipeCatalogRequest request,
            ConsumerCapabilities capabilities
    ) {
        if (player == null || request == null || capabilities == null) {
            return reject(
                    ConsumerDiagnosticCode.INVALID_REQUEST,
                    "catalog request is incomplete",
                    false
            );
        }
        if (player.server == null || !player.server.isSameThread()) {
            return reject(
                    ConsumerDiagnosticCode.WRONG_THREAD,
                    "catalog must be queried on the server thread",
                    true
            );
        }
        if (request.pageSize() > capabilities.maxPageSize()) {
            return reject(
                    ConsumerDiagnosticCode.PAGE_SIZE_EXCEEDED,
                    "catalog page exceeds the server limit",
                    false
            );
        }
        if (request.query() instanceof ConsumerRecipeQuery.ByOutputs outputs
                && outputs.outputs().size()
                > capabilities.maxRecipesPerSession()) {
            return reject(
                    ConsumerDiagnosticCode.BATCH_LIMIT_EXCEEDED,
                    "catalog output query exceeds the server limit",
                    false
            );
        }

        ServerLevel level = player.serverLevel();
        if (!RecipeIndex.isReady(level)) {
            return reject(
                    ConsumerDiagnosticCode.CATALOG_NOT_READY,
                    "recipe catalog is still indexing",
                    true
            );
        }

        long revision = CraftPlanningRevision.current();
        Snapshot current = catalog(level, revision);
        int start = 0;
        if (request.cursor() != 0L) {
            CursorState cursor = cursors.get(request.cursor());
            if (cursor == null) {
                return reject(
                        ConsumerDiagnosticCode.INVALID_REQUEST,
                        "catalog cursor is unknown",
                        false
                );
            }
            if (cursor.revision() != revision
                    || !cursor.query().equals(request.query())) {
                return reject(
                        ConsumerDiagnosticCode.CATALOG_REVISION_CHANGED,
                        "catalog revision or query changed",
                        true
                );
            }
            start = cursor.nextIndex();
        }

        List<ConsumerRecipeCandidate> matches =
                queryCandidates(current.candidates(), request.query());
        if (start > matches.size()) {
            return reject(
                    ConsumerDiagnosticCode.INVALID_REQUEST,
                    "catalog cursor is outside the current result",
                    false
            );
        }
        int end = Math.min(matches.size(), start + request.pageSize());
        Long nextCursor = end < matches.size()
                ? cursors.allocate(revision, request.query(), end)
                : null;
        ConsumerRecipeCatalogPage page = new ConsumerRecipeCatalogPage(
                request.query(),
                revision,
                request.cursor(),
                nextCursor,
                matches.subList(start, end)
        );
        return ConsumerResult.success(page);
    }

    private Snapshot catalog(ServerLevel level, long revision) {
        RecipeManager manager = level.getRecipeManager();
        Snapshot current = snapshot;
        if (current != null
                && current.manager() == manager
                && current.revision() == revision) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (current != null
                    && current.manager() == manager
                    && current.revision() == revision) {
                return current;
            }
            current = new Snapshot(
                    manager,
                    revision,
                    buildCandidates(level)
            );
            snapshot = current;
            cursors.clear();
            return current;
        }
    }

    private static List<ConsumerRecipeCandidate> buildCandidates(
            ServerLevel level
    ) {
        Map<ResourceLocation, RecipeIndex.Entry> unique = new TreeMap<>(
                Comparator.comparing(ResourceLocation::toString));
        for (List<RecipeIndex.Entry> entries : RecipeIndex.get(level).values()) {
            for (RecipeIndex.Entry entry : entries) {
                ResourceLocation recipeId = entry.recipe().getId();
                if (recipeId != null) unique.putIfAbsent(recipeId, entry);
            }
        }

        List<ConsumerRecipeCandidate> candidates = new ArrayList<>();
        Set<ResourceLocation> externalIds = new HashSet<>();
        List<Recipe<?>> recipes = new ArrayList<>(
                level.getRecipeManager().getRecipes());
        recipes.sort(Comparator.comparing(recipe ->
                recipe.getId().toString()));
        for (Recipe<?> recipe : recipes) {
            ModRecipeHandlers.Claim claim =
                    ModRecipeHandlers.claimFor(recipe);
            if (!(claim.winner() instanceof ExternalRecipeHandler external)) {
                continue;
            }
            RecipeView view = external.recipeView(recipe);
            if (view == null) continue;
            candidates.add(new ConsumerRecipeCandidate(
                    recipe.getId(),
                    view.processId(),
                    external.executionKind(),
                    view.outputs().stream()
                            .map(output -> output.resource())
                            .toList()
            ));
            externalIds.add(recipe.getId());
        }
        for (Map.Entry<ResourceLocation, RecipeIndex.Entry> item
                : unique.entrySet()) {
            if (externalIds.contains(item.getKey())) continue;
            RecipeIndex.Entry entry = item.getValue();
            Recipe<?> recipe = entry.recipe();
            ModRecipeHandlers.Claim claim = ModRecipeHandlers.claimFor(recipe);
            ModRecipeHandler handler = claim.ambiguous()
                    ? null : claim.winner();
            candidates.add(candidate(
                    recipe,
                    entry,
                    handler,
                    level
            ));
        }
        return List.copyOf(candidates);
    }

    private static ConsumerRecipeCandidate candidate(
            Recipe<?> recipe,
            RecipeIndex.Entry entry,
            ModRecipeHandler handler,
            ServerLevel level
    ) {
        if (handler instanceof ExternalRecipeHandler external) {
            RecipeView view = external.recipeView(recipe);
            return new ConsumerRecipeCandidate(
                    recipe.getId(),
                    view == null
                            ? external.integrationId()
                            : view.processId(),
                    external.executionKind(),
                    view == null
                            ? List.of()
                            : view.outputs().stream()
                            .map(output -> output.resource())
                            .toList()
            );
        }

        ResourceLocation processId = new ResourceLocation(
                RSIntegrationMod.MOD_ID, entry.modType().id());
        if (handler == null) {
            return new ConsumerRecipeCandidate(
                    recipe.getId(),
                    processId,
                    RecipeExecutionKind.NONE,
                    List.of()
            );
        }
        List<RecipeOutputSpec> specs;
        try {
            specs = handler.getDeclaredOutputs(
                    recipe, level.registryAccess());
        } catch (RuntimeException | LinkageError failure) {
            specs = List.of();
        }
        List<IntegrationResourceKey> outputs = new ArrayList<>();
        for (RecipeOutputSpec spec : specs) {
            ItemStack stack = spec.material().toStack(
                    Math.max(1, spec.maximumPerOperation()));
            if (!stack.isEmpty()) {
                outputs.add(IntegrationResourceKey.item(stack));
            }
        }
        RecipeExecutionKind executionKind = outputs.isEmpty()
                ? RecipeExecutionKind.NONE
                : RecipeExecutionKind.STANDARD_IO;
        return new ConsumerRecipeCandidate(
                recipe.getId(),
                processId,
                executionKind,
                outputs
        );
    }

    static List<ConsumerRecipeCandidate> queryCandidates(
            List<ConsumerRecipeCandidate> candidates,
            ConsumerRecipeQuery query
    ) {
        return candidates.stream()
                .filter(candidate -> matches(candidate, query))
                .sorted(Comparator.comparing(candidate ->
                        candidate.recipeId().toString()))
                .toList();
    }

    private static boolean matches(
            ConsumerRecipeCandidate candidate,
            ConsumerRecipeQuery query
    ) {
        if (query instanceof ConsumerRecipeQuery.All) return true;
        if (query instanceof ConsumerRecipeQuery.ByRecipeId byRecipeId) {
            return candidate.recipeId().equals(byRecipeId.recipeId());
        }
        if (query instanceof ConsumerRecipeQuery.ByProcess byProcess) {
            return candidate.processId().equals(byProcess.processId());
        }
        if (query instanceof ConsumerRecipeQuery.ByOutput byOutput) {
            return matchesAny(
                    candidate,
                    List.of(byOutput.output()),
                    byOutput.matchMode()
            );
        }
        ConsumerRecipeQuery.ByOutputs byOutputs =
                (ConsumerRecipeQuery.ByOutputs) query;
        return matchesAny(
                candidate, byOutputs.outputs(), byOutputs.matchMode());
    }

    private static boolean matchesAny(
            ConsumerRecipeCandidate candidate,
            List<IntegrationResourceKey> requested,
            ResourceMatchMode mode
    ) {
        for (IntegrationResourceKey query : requested) {
            for (IntegrationResourceKey declared : candidate.declaredOutputs()) {
                if (matchesResource(query, declared, mode)) return true;
            }
        }
        return false;
    }

    static boolean matchesResource(
            IntegrationResourceKey requested,
            IntegrationResourceKey declared,
            ResourceMatchMode mode
    ) {
        if (requested.kind() != declared.kind()
                || !requested.id().equals(declared.id())) {
            return false;
        }
        return switch (mode) {
            case ANY_IDENTITY -> true;
            case EXACT_IDENTITY ->
                    requested.identity().equals(declared.identity());
            case PARTIAL_IDENTITY ->
                    compoundContains(declared.identity(), requested.identity());
        };
    }

    private static boolean compoundContains(
            CompoundTag actual,
            CompoundTag expected
    ) {
        for (String key : expected.getAllKeys()) {
            Tag required = expected.get(key);
            Tag present = actual.get(key);
            if (present == null
                    || present.getId() != required.getId()) {
                return false;
            }
            if (required instanceof CompoundTag requiredCompound
                    && present instanceof CompoundTag presentCompound) {
                if (!compoundContains(presentCompound, requiredCompound)) {
                    return false;
                }
            } else if (!present.equals(required)) {
                return false;
            }
        }
        return true;
    }

    private static <T> ConsumerResult<T> reject(
            ConsumerDiagnosticCode code,
            String detail,
            boolean retryable
    ) {
        return ConsumerResult.rejected(new ConsumerDiagnostic(
                code, IntegrationSeverity.ERROR, detail, retryable));
    }

    private record Snapshot(
            RecipeManager manager,
            long revision,
            List<ConsumerRecipeCandidate> candidates
    ) {
        private Snapshot {
            candidates = List.copyOf(candidates);
        }
    }

    private record CursorState(
            long revision,
            ConsumerRecipeQuery query,
            int nextIndex
    ) {}

    private static final class CursorRegistry {
        private final int maximum;
        private final LinkedHashMap<Long, CursorState> states =
                new LinkedHashMap<>(16, 0.75F, true);
        private long nextToken = 1L;

        private CursorRegistry(int maximum) {
            this.maximum = maximum;
        }

        private synchronized Long allocate(
                long revision,
                ConsumerRecipeQuery query,
                int nextIndex
        ) {
            long token = nextToken++;
            states.put(token, new CursorState(revision, query, nextIndex));
            while (states.size() > maximum) {
                Long oldest = states.keySet().iterator().next();
                states.remove(oldest);
            }
            return token;
        }

        private synchronized CursorState get(long token) {
            return states.get(token);
        }

        private synchronized void clear() {
            states.clear();
        }
    }
}
