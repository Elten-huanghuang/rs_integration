package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 无加工状态的纯兑换执行基类。
 *
 * <p>一次操作直接代表剩余配方执行次数，材料与产物使用 {@code long}
 * 计算后按 {@link Integer#MAX_VALUE} 分片，不复制逐次执行循环。</p>
 */
public abstract class VirtualExchangeBatchDelegate extends AbstractBatchDelegate {
    @Nullable
    private VirtualExchangeRecipe exchangeRecipe;
    @Nullable
    private ServerPlayer player;
    private boolean done;
    private boolean resultInserted;
    private int preparedExecutions = 1;
    private final List<ItemStack> pendingResults = new ArrayList<>();

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.virtualTransaction();
    }

    /** 校验绑定方块或全局访问条件；通过后本次委托才允许兑换。 */
    protected abstract boolean validateExchangeAccess(
            ServerPlayer player, ResourceLocation recipeId,
            @Nullable ResourceLocation dim, BlockPos pos);

    @Nullable
    protected abstract VirtualExchangeRecipe resolveExchangeRecipe(
            ServerPlayer player, ResourceLocation recipeId);

    @Override
    public final boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                         @Nullable ResourceLocation dim, BlockPos pos) {
        resetRuntime();
        this.player = player;
        if (!validateExchangeAccess(player, recipeId, dim, pos)) return false;
        VirtualExchangeRecipe resolved = resolveExchangeRecipe(player, recipeId);
        if (resolved == null) return false;
        this.exchangeRecipe = resolved;
        if (storageEndpoint() == null) {
            setStorageEndpoint(CraftStorageEndpoints.resolveDefault(player).orElse(null));
        }
        return hasStorageAccess();
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        if (remainingOperations <= 0) {
            preparedExecutions = 0;
            return 0;
        }
        preparedExecutions = safePreparedExecutions(remainingOperations);
        return preparedExecutions;
    }

    @Override
    public int flatBatchOperationLimit(int configuredLimit) {
        int configured = configuredBatchLimit();
        return configured == 0 ? Integer.MAX_VALUE : configured;
    }

    @Override
    public boolean expandsFlatBatchOperationLimit() {
        return true;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        preparedExecutions = safePreparedExecutions(Math.max(1, executions));
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        return safePreparedExecutions(Math.max(1, totalOperations));
    }

    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (exchangeRecipe == null) return null;
        return scaledIngredientSpecs(exchangeRecipe.costSpecs(), 1);
    }

    @Override
    public List<IngredientSpec> getFlatBatchRequiredMaterials(int executions) {
        if (exchangeRecipe == null) return null;
        return scaledIngredientSpecs(
                exchangeRecipe.costSpecs(), Math.max(1, executions));
    }

    @Override
    public boolean startSingleOperation(ServerPlayer player) {
        if (exchangeRecipe == null) return false;
        this.player = player;
        if (storageEndpoint() == null) {
            setStorageEndpoint(CraftPacketUtils.resolveEndpointForCraft(player, null, null).orElse(null));
        }
        if (!hasStorageAccess()) return false;

        ExtractionLedger directLedger = new ExtractionLedger();
        directLedger.setStorageEndpoint(storageEndpoint());
        this.ledger = directLedger;
        List<ItemStack> reusableInputs = new ArrayList<>();
        for (IngredientSpec spec : exchangeRecipe.costSpecs()) {
            if (spec.isEmpty()) continue;
            int amount = CraftPacketUtils.requiredCount(spec, preparedExecutions);
            ItemStack extracted = directLedger.reserve(
                    spec.ingredient(), amount, storageEndpoint(), player, null, null);
            if (extracted.isEmpty() || extracted.getCount() != amount) {
                directLedger.rollback(player);
                return false;
            }
            if (spec.role() == DemandRole.CATALYST) {
                reusableInputs.add(extracted.copy());
            }
        }
        if (!directLedger.commitToEndpoint(storageEndpoint(), player)) {
            directLedger.rollback(player);
            return false;
        }

        List<ItemStack> results = collectScaledResults(
                player.serverLevel().registryAccess(), preparedExecutions);
        results.addAll(reusableInputs);
        for (ItemStack result : results) {
            ItemStack remainder = insertIntoStorage(player, result, false);
            if (!remainder.isEmpty()) PlayerUtils.safeGiveToStorage(player, remainder, storageEndpoint());
        }
        resultInserted = !results.isEmpty();
        done = true;
        return true;
    }

    @Override
    public boolean startSingleOperation(ServerPlayer player, ExtractionLedger sharedLedger) {
        this.player = player;
        useSharedLedger(sharedLedger);
        if (!hasStorageAccess() || exchangeRecipe == null) return false;
        if (!sharedLedger.commitToEndpoint(storageEndpoint(), player)) return false;
        preparePendingResults(List.of());
        done = true;
        return true;
    }

    @Override
    public boolean startPreparedOperation(ServerPlayer player, List<ItemStack> materials,
                                          ExtractionLedger sharedLedger) {
        this.player = player;
        useSharedLedger(sharedLedger);
        if (!hasStorageAccess() || exchangeRecipe == null) return false;
        if (!sharedLedger.commitToEndpoint(storageEndpoint(), player)) return false;
        preparePendingResults(materials);
        done = true;
        return true;
    }

    private void preparePendingResults(List<ItemStack> materials) {
        if (player == null || exchangeRecipe == null) return;
        pendingResults.clear();
        pendingResults.addAll(collectScaledResults(
                player.serverLevel().registryAccess(), preparedExecutions));
        List<IngredientSpec> specs = scaledIngredientSpecs(
                exchangeRecipe.costSpecs(), preparedExecutions);
        for (int index = 0; index < specs.size() && index < materials.size(); index++) {
            IngredientSpec spec = specs.get(index);
            ItemStack material = materials.get(index);
            if (spec.role() == DemandRole.CATALYST
                    && material != null && !material.isEmpty()) {
                pendingResults.add(material.copy());
            }
        }
    }

    private List<ItemStack> collectScaledResults(RegistryAccess access, int executions) {
        List<ItemStack> results = new ArrayList<>();
        results.addAll(scaledStacks(
                exchangeRecipe.resultItems(access), executions));
        results.addAll(scaledStacks(
                exchangeRecipe.secondaryItems(access), executions));
        return results;
    }

    @Override
    protected boolean isMachineCraftFinished(@Nullable ServerLevel level,
                                             @Nullable BlockEntity blockEntity) {
        return done;
    }

    @Override
    public ItemStack collectResult(@NotNull ServerPlayer player) {
        if (resultInserted || pendingResults.isEmpty()) return ItemStack.EMPTY;
        ItemStack first = pendingResults.remove(0);
        if (pendingResults.isEmpty()) done = false;
        return first;
    }

    @Override
    public List<ItemStack> collectAllResults(@NotNull ServerPlayer player) {
        if (resultInserted || pendingResults.isEmpty()) return List.of();
        List<ItemStack> results = List.copyOf(pendingResults);
        pendingResults.clear();
        done = false;
        return results;
    }

    @Override
    protected void clearMachineState(@Nullable BlockEntity blockEntity, @Nullable ServerPlayer player) {
        resetRuntime();
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        resetRuntime();
    }

    private void resetRuntime() {
        done = false;
        resultInserted = false;
        preparedExecutions = 1;
        pendingResults.clear();
        exchangeRecipe = null;
        player = null;
        resetState();
    }

    private int safePreparedExecutions(int requested) {
        int bounded = boundedExecutions(requested);
        if (exchangeRecipe == null) return bounded;
        List<ItemStack> results = player == null ? List.of()
                : exchangeRecipe.resultItems(player.serverLevel().registryAccess());
        List<ItemStack> secondary = player == null ? List.of()
                : exchangeRecipe.secondaryItems(player.serverLevel().registryAccess());
        List<ItemStack> allResults = new ArrayList<>(results.size() + secondary.size());
        allResults.addAll(results);
        allResults.addAll(secondary);
        return safeExecutionsForSpecs(bounded, exchangeRecipe.costSpecs(), allResults);
    }

    @Override
    @Nullable
    public BlockPos getMachinePos() {
        return null;
    }

    static int boundedExecutions(int requested) {
        return boundedExecutions(requested, configuredBatchLimit());
    }

    static int boundedExecutions(int requested, int limit) {
        requested = Math.max(1, requested);
        limit = Math.max(0, limit);
        return limit == 0 ? requested : Math.min(requested, limit);
    }

    static int safeExecutions(int requested, List<ItemStack> costs, List<ItemStack> results) {
        List<IngredientSpec> specs = costs == null ? List.of()
                : costs.stream()
                .filter(stack -> stack != null && !stack.isEmpty())
                .map(stack -> new IngredientSpec(
                        Ingredient.of(stack.copyWithCount(1)), stack.getCount()))
                .toList();
        return safeExecutionsForSpecs(requested, specs, results);
    }

    static int safeExecutionsForSpecs(int requested, List<IngredientSpec> costs,
                                      List<ItemStack> results) {
        int safe = Math.max(1, requested);
        if (costs != null) {
            for (IngredientSpec spec : costs) {
                if (spec == null || spec.isEmpty()
                        || spec.role() == DemandRole.CATALYST) continue;
                safe = Math.min(safe, Math.max(1, Integer.MAX_VALUE / spec.count()));
            }
        }
        if (results != null) {
            for (ItemStack stack : results) {
                if (stack == null || stack.isEmpty()) continue;
                safe = Math.min(safe, Math.max(1, Integer.MAX_VALUE / stack.getCount()));
            }
        }
        return safe;
    }

    static int configuredBatchLimit() {
        try {
            return Math.max(0, RSIntegrationConfig.CRAFTING_VIRTUAL_EXCHANGE_BATCH_LIMIT.get());
        } catch (RuntimeException ignored) {
            return RSIntegrationConfig.DEFAULT_CRAFTING_VIRTUAL_EXCHANGE_BATCH_LIMIT;
        }
    }

    static List<IngredientSpec> requiredMaterials(List<ItemStack> perOperation, int executions) {
        if (perOperation == null || perOperation.isEmpty() || executions <= 0) return List.of();
        return scaledIngredientSpecs(perOperation.stream()
                .filter(stack -> stack != null && !stack.isEmpty())
                .map(stack -> new IngredientSpec(
                        Ingredient.of(stack.copyWithCount(1)), stack.getCount()))
                .toList(), executions);
    }

    static List<IngredientSpec> scaledIngredientSpecs(
            List<IngredientSpec> perOperation, int executions) {
        if (perOperation == null || perOperation.isEmpty() || executions <= 0) return List.of();
        List<IngredientSpec> result = new ArrayList<>();
        for (IngredientSpec spec : perOperation) {
            if (spec == null || spec.isEmpty()) continue;
            long total = spec.role() == DemandRole.CATALYST
                    ? spec.count() : (long) spec.count() * executions;
            while (total > 0L) {
                int chunk = (int) Math.min(Integer.MAX_VALUE, total);
                result.add(new IngredientSpec(
                        spec.ingredient(), chunk, spec.consumption()));
                total -= chunk;
            }
        }
        return List.copyOf(result);
    }

    static List<ItemStack> scaledStacks(List<ItemStack> perOperation, int executions) {
        if (perOperation == null || perOperation.isEmpty() || executions <= 0) return List.of();
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : perOperation) {
            if (stack == null || stack.isEmpty()) continue;
            long total = (long) stack.getCount() * executions;
            while (total > 0L) {
                int chunk = (int) Math.min(Integer.MAX_VALUE, total);
                result.add(stack.copyWithCount(chunk));
                total -= chunk;
            }
        }
        return List.copyOf(result);
    }
}
