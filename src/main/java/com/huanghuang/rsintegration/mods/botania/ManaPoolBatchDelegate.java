package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import vazkii.botania.api.recipe.ManaInfusionRecipe;
import vazkii.botania.common.block.block_entity.mana.ManaPoolBlockEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/** Real single-input Mana Pool operation. Catalyst matching is performed by Botania. */
public final class ManaPoolBatchDelegate extends AbstractBatchDelegate {
    static final int MAX_PHYSICAL_BATCH = 1024;
    static final int MAX_PARALLEL_WORKER_BATCH = 128;
    private ServerLevel level;
    private BlockPos bindingPos;
    private BlockPos poolPos;
    private ManaInfusionRecipe recipe;
    private INetwork rsNetwork;
    private ItemStack expected = ItemStack.EMPTY;
    private boolean started;
    private int requestedBatch = 1;
    private long startTick;
    private final java.util.Set<java.util.UUID> inputEntityIds = new java.util.HashSet<>();
    private java.util.Set<java.util.UUID> entitiesBefore = java.util.Set.of();

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   ResourceLocation dim, @Nonnull BlockPos pos) {
        return prepareInternal(player, recipeId, dim, pos).state() == PreparationState.READY;
    }

    @Override
    public PreparationResult prepare(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                     ResourceLocation dim, @Nonnull BlockPos pos) {
        return prepareInternal(player, recipeId, dim, pos);
    }

    private PreparationResult prepareInternal(ServerPlayer player, ResourceLocation recipeId,
                                              ResourceLocation dim, BlockPos pos) {
        this.bindingPos = pos.immutable();
        this.machineDim = dim;
        this.machineServer = player.getServer();
        this.level = null;
        this.poolPos = null;
        this.recipe = null;
        this.expected = ItemStack.EMPTY;
        this.requestedBatch = 1;
        this.inputEntityIds.clear();
        this.rsNetwork = null;

        ServerLevel resolved = dim == null ? player.serverLevel() : player.getServer().getLevel(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
        if (resolved == null) return PreparationResult.retry("Mana Pool dimension is unavailable");

        var found = resolved.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(found instanceof ManaInfusionRecipe r)) {
            return PreparationResult.fatal("Recipe is not a Botania Mana Pool infusion: " + recipeId);
        }
        ItemStack result = r.getResultItem(resolved.registryAccess());
        if (result == null || result.isEmpty()) {
            return PreparationResult.fatal("Mana Pool recipe has no output: " + recipeId);
        }
        if (r.getIngredients().isEmpty() || r.getIngredients().get(0).isEmpty()) {
            return PreparationResult.fatal("Mana Pool recipe has no input: " + recipeId);
        }

        boolean directlyBoundPool = resolved.getBlockEntity(bindingPos) instanceof ManaPoolBlockEntity;
        boolean poolAboveBinding = resolved.getBlockEntity(bindingPos.above()) instanceof ManaPoolBlockEntity;
        var catalyst = r.getRecipeCatalyst();
        boolean catalystMatches = catalyst != null && catalyst.test(resolved.getBlockState(bindingPos));
        boolean catalystMatchesBelowPool = catalyst != null && directlyBoundPool
                && catalyst.test(resolved.getBlockState(bindingPos.below()));
        ManaPoolBindingRules.Assessment assessment = ManaPoolBindingRules.assess(
                bindingPos, directlyBoundPool, poolAboveBinding, catalyst != null,
                catalystMatches, catalystMatchesBelowPool);
        if (assessment.state() == ManaPoolBindingRules.State.RETRY) {
            return PreparationResult.retry(assessment.detail());
        }
        if (assessment.state() == ManaPoolBindingRules.State.FATAL || assessment.poolPos() == null) {
            return PreparationResult.fatal(assessment.detail());
        }

        this.level = resolved;
        this.poolPos = assessment.poolPos();
        this.recipe = r;
        this.expected = result.copy();
        this.rsNetwork = resolveNetwork(player);
        return PreparationResult.ready();
    }

    @Override
    public boolean acceptsMachineWithoutBlockEntity(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        // Catalyst bindings point at the catalyst block, which intentionally has no
        // block entity. The actual leased machine is the Mana Pool directly above it.
        return level.getBlockEntity(pos.above()) instanceof ManaPoolBlockEntity;
    }
    @Override public List<IngredientSpec> getRequiredMaterials() {
        return recipe == null ? null : List.of(new IngredientSpec(recipe.getIngredients().get(0), 1));
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        int mana = level != null && poolPos != null
                && level.getBlockEntity(poolPos) instanceof ManaPoolBlockEntity pool
                ? pool.getCurrentMana() : 0;
        int manaCost = recipe == null ? 0 : recipe.getManaToConsume();
        requestedBatch = physicalBatchSize(remainingOperations, mana, manaCost);
        return requestedBatch;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        requestedBatch = graphBatchSize(executions);
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        return parallelWorkerBatchSize(totalOperations, workerCount);
    }

    static int parallelWorkerBatchSize(int totalOperations, int workerCount) {
        if (totalOperations <= 0 || workerCount <= 0) return 1;
        int evenShare = (totalOperations + workerCount - 1) / workerCount;
        return Math.max(1, Math.min(MAX_PARALLEL_WORKER_BATCH, evenShare));
    }

    static int graphBatchSize(int executions) {
        return Math.max(1, Math.min(executions, MAX_PHYSICAL_BATCH));
    }

    static int physicalBatchSize(int remainingOperations, int availableMana, int manaPerItem) {
        if (remainingOperations <= 0) return 0;
        int requested = Math.min(remainingOperations, MAX_PHYSICAL_BATCH);
        if (manaPerItem <= 0) return requested;
        int affordable = Math.max(0, availableMana) / manaPerItem;
        // Keep the normal wait-for-mana behaviour when even one operation is not
        // currently affordable; larger batches are admitted only atomically.
        return affordable <= 0 ? 1 : Math.max(1, Math.min(requested, affordable));
    }

    @Override public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        if (recipe == null || level == null) return false;
        if (rsNetwork == null) rsNetwork = resolveNetwork(player);
        if (rsNetwork == null) return false;
        List<ItemStack> extracted = BotaniaDelegateSupport.extractAtomically(rsNetwork, getRequiredMaterials());
        if (extracted.isEmpty()) return false;
        ItemStack input = extracted.get(0);
        requestedBatch = Math.max(1, input.getCount());
        if (startEntities(input)) return true;
        refundStandalone(player, input);
        return false;
    }

    @Override public boolean tryStartSingleCraft(@Nonnull ServerPlayer player, @Nonnull ExtractionLedger sharedLedger) {
        return false;
    }

    @Override public boolean tryStartWithMaterials(@Nonnull ServerPlayer player, @Nonnull List<ItemStack> materials,
                                                    @Nonnull ExtractionLedger sharedLedger) {
        return materials.size() == 1 && !materials.get(0).isEmpty()
                && startEntities(materials.get(0).copy());
    }

    private boolean startEntities(ItemStack input) {
        if (!(level.getBlockEntity(poolPos) instanceof ManaPoolBlockEntity pool)) return false;
        entitiesBefore = BotaniaDelegateSupport.snapshot(level, new AABB(poolPos).inflate(1.5));
        inputEntityIds.clear();
        int remaining = input.getCount();
        int stackLimit = Math.max(1, input.getMaxStackSize());
        while (remaining > 0) {
            int count = Math.min(remaining, stackLimit);
            ItemEntity entity = new ItemEntity(level, poolPos.getX()+0.5,
                    poolPos.getY()+1.15, poolPos.getZ()+0.5, input.copyWithCount(count));
            entity.setDeltaMovement(0, 0, 0);
            // This operation owns the entities; nearby players and collectors must not steal them.
            BotaniaDelegateSupport.protectOperationInput(entity);
            if (!level.addFreshEntity(entity)) {
                discardOwnedInputs();
                return false;
            }
            inputEntityIds.add(entity.getUUID());
            remaining -= count;
        }
        requestedBatch = input.getCount();
        started = true; startTick = level.getGameTime(); markCraftStarted(); return true;
    }

    private INetwork resolveNetwork(ServerPlayer player) {
        if (level == null || bindingPos == null) return null;
        INetwork resolved = CraftPacketUtils.resolveNetworkForCraft(
                player, level.dimension(), bindingPos);
        if (resolved == null && poolPos != null && !poolPos.equals(bindingPos)) {
            resolved = CraftPacketUtils.resolveNetworkForCraft(player, level.dimension(), poolPos);
        }
        return resolved;
    }

    @Override protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        if (!started) return false;
        AABB box = new AABB(poolPos).inflate(1.5);
        int outputCount = level.getEntitiesOfClass(ItemEntity.class, box, this::isCraftOutput)
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum();
        return outputCount >= expectedOutputCount();
    }

    @Override public ItemStack collectResult(@Nonnull ServerPlayer player) {
        if (level == null) return ItemStack.EMPTY;
        AABB box = new AABB(poolPos).inflate(1.5);
        int collected = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box, this::isCraftOutput)) {
            collected += e.getItem().getCount();
            e.discard();
        }
        return collected <= 0 ? ItemStack.EMPTY : expected.copyWithCount(collected);
    }

    private boolean isCraftOutput(ItemEntity entity) {
        return entity.isAlive()
                && !inputEntityIds.contains(entity.getUUID())
                && BotaniaDelegateSupport.isNew(entity, entitiesBefore)
                && !entity.getItem().isEmpty()
                && ItemStack.isSameItemSameTags(entity.getItem(), expected)
                && entity.getItem().getCount() >= expected.getCount()
                && level.getGameTime() >= startTick;
    }

    @Override protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        if (level == null || inputEntityIds.isEmpty()) return;
        for (java.util.UUID id : java.util.List.copyOf(inputEntityIds)) {
            var entity = level.getEntity(id);
            if (entity instanceof ItemEntity item && item.isAlive()) {
                ItemStack stack = item.getItem().copy();
                item.discard();
                if (!usingSharedLedger) refundStandalone(player, stack);
            }
        }
        inputEntityIds.clear();
    }

    private void discardOwnedInputs() {
        if (level == null) return;
        for (java.util.UUID id : java.util.List.copyOf(inputEntityIds)) {
            var entity = level.getEntity(id);
            if (entity != null && entity.isAlive()) entity.discard();
        }
        inputEntityIds.clear();
    }

    private int expectedOutputCount() {
        long count = (long) Math.max(1, expected.getCount()) * Math.max(1, requestedBatch);
        return (int) Math.min(Integer.MAX_VALUE, count);
    }

    private void refundStandalone(@Nullable ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemStack leftover = rsNetwork == null
                ? stack.copy()
                : rsNetwork.insertItem(stack.copy(), stack.getCount(), Action.PERFORM);
        if (leftover.isEmpty()) return;
        if (player != null) {
            PlayerUtils.safeGiveToPlayer(player, leftover, rsNetwork);
            return;
        }
        if (level != null && poolPos != null) {
            ItemEntity drop = new ItemEntity(level,
                    poolPos.getX() + 0.5, poolPos.getY() + 1.15, poolPos.getZ() + 0.5,
                    leftover.copy());
            drop.setDeltaMovement(0, 0.2, 0);
            level.addFreshEntity(drop);
        }
    }
    @Override
    public com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities concurrencyCapabilities() {
        return new com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities(
                com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS,
                com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                java.util.List.of());
    }
    @Override public ItemStack getExpectedOutput() {
        return expected.isEmpty() ? null : expected.copyWithCount(expectedOutputCount());
    }
    @Override public AABB getOutputCaptureRegion() {
        return poolPos == null ? null : captureRegion(poolPos);
    }

    static AABB captureRegion(BlockPos poolPos) {
        // Inputs and Botania's replacement outputs stay over the pool centre.
        // A pool-local box lets adjacent bound pools own distinct capture zones.
        return new AABB(poolPos.getX() + 0.05, poolPos.getY() + 0.70, poolPos.getZ() + 0.05,
                poolPos.getX() + 0.95, poolPos.getY() + 1.80, poolPos.getZ() + 0.95);
    }
    @Override public BlockPos getMachinePos() { return poolPos; }
    @Override public void onBatchFinished(@Nonnull ServerPlayer player) { resetState(); }
}
