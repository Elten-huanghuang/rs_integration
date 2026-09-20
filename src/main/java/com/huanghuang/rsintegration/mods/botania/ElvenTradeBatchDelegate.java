package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import vazkii.botania.api.recipe.ElvenTradeRecipe;
import vazkii.botania.common.block.block_entity.AlfheimPortalBlockEntity;
import vazkii.botania.xplat.XplatAbstractions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Queues several identical trades; Botania still resolves one recipe every few ticks. */
public final class ElvenTradeBatchDelegate extends AbstractBatchDelegate {
    private static final int FALLBACK_BATCH_LIMIT = 64;

    private ServerLevel level;
    private BlockPos pos;
    private ElvenTradeRecipe recipe;
    private INetwork rsNetwork;
    private List<ItemStack> expected = List.of();
    private boolean started;
    private int requestedBatch = 1;
    private long startTick;
    private Set<UUID> entitiesBefore = Set.of();
    private final Set<UUID> inputEntityIds = new java.util.HashSet<>();
    private List<ItemStack> immediateStartRecovery = List.of();

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dimension, @Nonnull BlockPos machinePos) {
        resetLocalState();
        pos = machinePos.immutable();
        machineDim = dimension;
        machineServer = player.getServer();
        level = dimension == null ? player.serverLevel() : player.getServer().getLevel(
                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension));
        if (level == null || !(level.getBlockEntity(pos) instanceof AlfheimPortalBlockEntity portal)
                || !portalQueueEmpty(portal)) return false;
        var found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(found instanceof ElvenTradeRecipe elvenRecipe)) return false;
        recipe = elvenRecipe;
        expected = consolidateOutputs(elvenRecipe.getOutputs(), 1);
        if (storageEndpoint() == null) {
            rsNetwork = CraftPacketUtils.resolveNetworkForCraft(player, level.dimension(), pos);
        }
        return (rsNetwork != null || hasStorageAccess()) && !expected.isEmpty();
    }

    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) specs.add(new IngredientSpec(ingredient, 1));
        }
        return specs;
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        requestedBatch = configuredBatchSize(remainingOperations);
        return requestedBatch;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        requestedBatch = configuredBatchSize(executions);
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        if (!batchingEnabled()) return 1;
        return ParallelBatchSizing.boundedEvenShare(totalOperations, workerCount, configuredLimit());
    }

    private int configuredBatchSize(int operations) {
        if (!batchingEnabled()) return operations > 0 ? 1 : 0;
        return ParallelBatchSizing.boundedBatch(operations, configuredLimit());
    }

    private static boolean batchingEnabled() {
        return RSIntegrationConfig.ENABLE_BOTANIA_ELVEN_TRADE_INPUT_BUFFER == null
                || RSIntegrationConfig.ENABLE_BOTANIA_ELVEN_TRADE_INPUT_BUFFER.get();
    }

    private static int configuredLimit() {
        return RSIntegrationConfig.BOTANIA_ELVEN_TRADE_INPUT_BUFFER_LIMIT == null
                ? FALLBACK_BATCH_LIMIT
                : RSIntegrationConfig.BOTANIA_ELVEN_TRADE_INPUT_BUFFER_LIMIT.get();
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        List<ItemStack> materials = storageEndpoint() != null
                ? BotaniaDelegateSupport.extractAtomically(storageEndpoint(), player, getRequiredMaterials())
                : BotaniaDelegateSupport.extractAtomically(rsNetwork, getRequiredMaterials());
        if (materials.isEmpty()) return false;
        requestedBatch = 1;
        if (start(materials)) return true;
        if (storageEndpoint() != null) BotaniaDelegateSupport.refund(storageEndpoint(), player, materials);
        else if (rsNetwork != null) BotaniaDelegateSupport.refund(rsNetwork, materials);
        return false;
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player,
                                       @Nonnull ExtractionLedger sharedLedger) {
        return false;
    }

    @Override
    public boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                         @Nonnull List<ItemStack> materials,
                                         @Nonnull ExtractionLedger sharedLedger) {
        useSharedLedger(sharedLedger);
        return start(materials);
    }

    private boolean start(List<ItemStack> materials) {
        immediateStartRecovery = usingSharedLedger ? copyStacks(materials) : List.of();
        if (level == null || pos == null || recipe == null
                || !(level.getBlockEntity(pos) instanceof AlfheimPortalBlockEntity portal)
                || !portalQueueEmpty(portal)) return false;
        int operations = inferOperations(getRequiredMaterials(), materials);
        if (operations <= 0 || operations > Math.max(1, requestedBatch)
                || operations > configuredLimit() || (!batchingEnabled() && operations > 1)) return false;
        List<ItemStack> oneOperation = oneOperationMaterials(
                getRequiredMaterials(), materials, operations);
        if (oneOperation.isEmpty()) return false;
        List<ItemStack> dynamicOutputs = recipe.getOutputs(oneOperation);
        if (dynamicOutputs == null || dynamicOutputs.isEmpty()) return false;
        expected = consolidateOutputs(dynamicOutputs, operations);
        if (expected.isEmpty()) return false;

        entitiesBefore = BotaniaDelegateSupport.snapshot(level, captureRegion(pos));
        inputEntityIds.clear();
        for (ItemStack material : materials) {
            ItemEntity entity = new ItemEntity(level,
                    pos.getX() + 0.5D, pos.getY() + 1.5D, pos.getZ() + 0.5D,
                    material.copy());
            entity.setDeltaMovement(0.0D, 0.0D, 0.0D);
            BotaniaDelegateSupport.protectOperationInput(entity);
            if (!level.addFreshEntity(entity)) {
                discardOwnedInputEntities(false, null);
                return false;
            }
            inputEntityIds.add(entity.getUUID());
        }
        requestedBatch = operations;
        immediateStartRecovery = List.of();
        started = true;
        startTick = level.getGameTime();
        markCraftStarted();
        return true;
    }

    static int inferOperations(List<IngredientSpec> specs, List<ItemStack> materials) {
        if (specs == null || specs.isEmpty() || materials == null
                || specs.size() != materials.size()) return 0;
        int operations = -1;
        for (int index = 0; index < specs.size(); index++) {
            IngredientSpec spec = specs.get(index);
            ItemStack material = materials.get(index);
            if (material == null || material.isEmpty() || spec.count() <= 0
                    || !spec.ingredient().test(material)
                    || material.getCount() % spec.count() != 0) return 0;
            int entryOperations = material.getCount() / spec.count();
            if (operations < 0) operations = entryOperations;
            else if (operations != entryOperations) return 0;
        }
        return Math.max(0, operations);
    }

    static List<ItemStack> oneOperationMaterials(List<IngredientSpec> specs,
                                                 List<ItemStack> materials,
                                                 int operations) {
        if (operations <= 0 || inferOperations(specs, materials) != operations) return List.of();
        List<ItemStack> single = new ArrayList<>(materials.size());
        for (int index = 0; index < materials.size(); index++) {
            single.add(materials.get(index).copyWithCount(specs.get(index).count()));
        }
        return List.copyOf(single);
    }

    static List<ItemStack> consolidateOutputs(List<ItemStack> outputs, int operations) {
        if (outputs == null || outputs.isEmpty() || operations <= 0) return List.of();
        Map<OutputKey, ItemStack> combined = new LinkedHashMap<>();
        for (ItemStack output : outputs) {
            if (output == null || output.isEmpty()) continue;
            OutputKey key = new OutputKey(output);
            ItemStack aggregate = combined.computeIfAbsent(key, ignored -> output.copyWithCount(0));
            long count = (long) aggregate.getCount() + (long) output.getCount() * operations;
            aggregate.setCount((int) Math.min(Integer.MAX_VALUE, count));
        }
        return combined.values().stream().map(ItemStack::copy).toList();
    }

    private record OutputKey(ItemStack stack) {
        private OutputKey {
            stack = stack.copyWithCount(1);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof OutputKey key && ItemStack.isSameItemSameTags(stack, key.stack);
        }

        @Override
        public int hashCode() {
            int itemHash = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).hashCode();
            return 31 * itemHash + (stack.hasTag() ? stack.getTag().hashCode() : 0);
        }
    }

    private static boolean portalQueueEmpty(AlfheimPortalBlockEntity portal) {
        try {
            return portal.saveWithoutMetadata().getInt("stackCount") == 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    protected boolean isMachineCraftFinished(@Nonnull ServerLevel currentLevel,
                                             @Nonnull BlockEntity blockEntity) {
        if (!started) return false;
        List<ItemEntity> found = outputs();
        for (ItemStack wanted : expected) {
            int count = found.stream()
                    .filter(entity -> ItemStack.isSameItemSameTags(entity.getItem(), wanted))
                    .mapToInt(entity -> entity.getItem().getCount()).sum();
            if (count < wanted.getCount()) return false;
        }
        return true;
    }

    private List<ItemEntity> outputs() {
        if (level == null || pos == null) return List.of();
        return level.getEntitiesOfClass(ItemEntity.class, captureRegion(pos), entity ->
                BotaniaDelegateSupport.isNew(entity, entitiesBefore)
                        && !inputEntityIds.contains(entity.getUUID())
                        && level.getGameTime() >= startTick
                        && XplatAbstractions.INSTANCE.itemFlagsComponent(entity).elvenPortalSpawned
                        && expected.stream().anyMatch(wanted ->
                        ItemStack.isSameItemSameTags(wanted, entity.getItem())));
    }

    @Override
    public ItemStack collectResult(@Nonnull ServerPlayer player) {
        List<ItemStack> all = collectAllResults(player);
        return all.isEmpty() ? ItemStack.EMPTY : all.get(0);
    }

    @Override
    public List<ItemStack> collectAllResults(@Nonnull ServerPlayer player) {
        List<ItemStack> results = new ArrayList<>();
        for (ItemEntity entity : outputs()) {
            results.add(entity.getItem().copy());
            entity.discard();
        }
        return results;
    }

    @Override
    public boolean collectsPhysicalSecondaryOutputs() {
        return true;
    }

    @Override
    public ExpectedProduction getExpectedProduction() {
        return expected.isEmpty() ? null
                : new ExpectedProduction(expected.get(0), expected.get(0).getCount());
    }

    @Override
    protected void clearMachineState(BlockEntity blockEntity, @Nullable ServerPlayer player) {
        recordFailureRecoveredInputs(recoverFailedStartOrPhysicalInputs(player));
        resetLocalState();
        resetState();
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        recordFailureRecoveredInputs(recoverFailedStartOrPhysicalInputs(player));
        resetLocalState();
        resetState();
    }

    private List<ItemStack> recoverFailedStartOrPhysicalInputs(@Nullable ServerPlayer player) {
        if (immediateStartRecovery.isEmpty()) {
            return discardOwnedInputEntities(!usingSharedLedger, player);
        }
        discardOwnedInputEntities(false, null);
        List<ItemStack> recovered = copyStacks(immediateStartRecovery);
        if (!usingSharedLedger) {
            for (ItemStack stack : recovered) refundStandalone(player, stack);
        }
        immediateStartRecovery = List.of();
        return recovered;
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) return List.of();
        return stacks.stream().filter(stack -> stack != null && !stack.isEmpty())
                .map(ItemStack::copy).toList();
    }

    private List<ItemStack> discardOwnedInputEntities(boolean refund, @Nullable ServerPlayer player) {
        List<ItemStack> recovered = new ArrayList<>();
        if (level == null) return recovered;
        for (UUID inputId : List.copyOf(inputEntityIds)) {
            Entity raw = level.getEntity(inputId);
            if (!(raw instanceof ItemEntity item) || !item.isAlive()) continue;
            ItemStack removed = item.getItem().copy();
            item.discard();
            recovered.add(removed.copy());
            if (refund) refundStandalone(player, removed);
        }
        inputEntityIds.clear();
        return recovered;
    }

    private void refundStandalone(@Nullable ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        if (storageEndpoint() == null) this.network = rsNetwork;
        ItemStack leftover = insertIntoStorage(player, stack, false);
        if (leftover.isEmpty()) return;
        if (player != null) PlayerUtils.safeGiveToPlayer(player, leftover, rsNetwork);
        else if (level != null && pos != null) level.addFreshEntity(new ItemEntity(level,
                pos.getX() + 0.5D, pos.getY() + 1.5D, pos.getZ() + 0.5D, leftover));
    }

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return new BatchConcurrencyCapabilities(
                BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS,
                BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                List.of());
    }

    @Override
    public ItemStack getExpectedOutput() {
        return expected.isEmpty() ? null : expected.get(0).copy();
    }

    @Override
    public AABB getOutputCaptureRegion() {
        return pos == null ? null : captureRegion(pos);
    }

    static AABB captureRegion(BlockPos portalPos) {
        return new AABB(portalPos.getX() + 0.05D, portalPos.getY() - 0.25D,
                portalPos.getZ() + 0.05D, portalPos.getX() + 0.95D,
                portalPos.getY() + 2.5D, portalPos.getZ() + 0.95D);
    }

    @Override
    public BlockPos getMachinePos() {
        return pos;
    }

    @Override
    public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        resetLocalState();
        resetState();
    }

    private void resetLocalState() {
        level = null;
        pos = null;
        recipe = null;
        rsNetwork = null;
        expected = List.of();
        started = false;
        requestedBatch = 1;
        startTick = 0L;
        entitiesBefore = Set.of();
        inputEntityIds.clear();
        immediateStartRecovery = List.of();
    }
}
