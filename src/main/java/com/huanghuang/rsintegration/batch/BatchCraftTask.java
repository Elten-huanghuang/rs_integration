package com.huanghuang.rsintegration.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.integration.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BatchCraftTask {




    private final UUID playerId;
    private final ResourceLocation recipeId;
    private final int totalCount;

    // Direct delegate path (existing)
    private final IBatchDelegate delegate;

    // Recursive chain path (new)
    private final List<CraftingResolver.ResolutionStep> resolvedSteps;
    private final INetwork network;

    private int completedCount;
    private int waitTicks;
    private TaskState state = TaskState.IDLE;
    private AsyncCraftChain activeChain;

    private enum TaskState { IDLE, EXTRACTING, WAITING, COLLECTING, WAITING_CHAIN, DONE, FAILED }

    /** Direct delegate constructor (existing multi-block batch behavior). */
    public BatchCraftTask(UUID playerId, ResourceLocation recipeId, int totalCount, IBatchDelegate delegate) {
        this.playerId = playerId;
        this.recipeId = recipeId;
        this.totalCount = totalCount;
        this.delegate = delegate;
        this.resolvedSteps = null;
        this.network = null;
    }

    /** Recursive chain constructor — each iteration runs the full resolution chain. */
    public BatchCraftTask(UUID playerId, ResourceLocation recipeId, int totalCount,
                          List<CraftingResolver.ResolutionStep> resolvedSteps, INetwork network) {
        this.playerId = playerId;
        this.recipeId = recipeId;
        this.totalCount = totalCount;
        this.delegate = null;
        this.resolvedSteps = resolvedSteps;
        this.network = network;
    }

    public UUID getPlayerId() { return playerId; }
    public ResourceLocation getRecipeId() { return recipeId; }
    public int getTotalCount() { return totalCount; }
    public int getCompletedCount() { return completedCount; }

    public void markFailed(String reason) {
        state = TaskState.FAILED;
    }

    void tick(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null || player.isRemoved()) {
            if (activeChain != null) {
                activeChain.abort("Player disconnected");
                activeChain = null;
            }
            if (delegate != null) {
                delegate.onBatchFailed(null, "Player disconnected");
            }
            state = TaskState.FAILED;
            return;
        }

        // ── Recursive chain path ────────────────────────────────
        if (resolvedSteps != null) {
            tickRecursive(player);
            return;
        }

        // ── Direct delegate path (legacy) ────────────────────────
        switch (state) {
            case IDLE:
                state = TaskState.EXTRACTING;
                // fall through

            case EXTRACTING:
                if (completedCount >= totalCount) {
                    state = TaskState.DONE;
                    return;
                }

                boolean started = delegate.tryStartSingleCraft(player);

                if (!started) {
                    delegate.onBatchFailed(player, "materials_exhausted");
                    player.displayClientMessage(
                            Component.translatable("rsi.batch.failed",
                                    completedCount,
                                    Component.translatable("rsi.batch.error.materials_exhausted")),
                            false);
                    state = TaskState.FAILED;
                    return;
                }

                // Check if delegate completed synchronously (e.g. Eidolon)
                if (delegate.isCraftComplete(player.serverLevel())) {
                    ItemStack result = delegate.collectResult(player);
                    insertIntoRS(player, result);
                    completedCount++;
                    player.displayClientMessage(
                            Component.translatable("rsi.batch.progress", completedCount, totalCount), true);
                    state = TaskState.EXTRACTING;
                } else {
                    state = TaskState.WAITING;
                    waitTicks = 0;
                }
                return;

            case WAITING:
                waitTicks++;
                if (waitTicks > RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20) {
                    delegate.onBatchFailed(player, "timeout");
                    player.displayClientMessage(
                            Component.translatable("rsi.batch.failed",
                                    completedCount,
                                    Component.translatable("rsi.batch.error.timeout")),
                            false);
                    state = TaskState.FAILED;
                    return;
                }

                if (delegate.isCraftComplete(player.serverLevel())) {
                    state = TaskState.COLLECTING;
                }
                return;

            case COLLECTING:
                ItemStack result = delegate.collectResult(player);
                insertIntoRS(player, result);
                completedCount++;
                player.displayClientMessage(
                        Component.translatable("rsi.batch.progress", completedCount, totalCount), true);
                state = TaskState.EXTRACTING;
                return;

            case DONE:
                delegate.onBatchFinished(player);
                player.displayClientMessage(
                        Component.translatable("rsi.batch.complete", completedCount), false);
                BatchCraftManager.getInstance().removeTask(this);
                RSIntegrationMod.LOGGER.info("[RSI-Batch] Batch complete: recipe={} total={}",
                        recipeId, completedCount);
                return;

            case FAILED:
                BatchCraftManager.getInstance().removeTask(this);
        }
    }

    // ── recursive chain tick ─────────────────────────────────

    private void tickRecursive(ServerPlayer player) {
        switch (state) {
            case IDLE:
                state = TaskState.WAITING_CHAIN;
                waitTicks = 0;
                // fall through to start first iteration

            case WAITING_CHAIN:
                if (completedCount >= totalCount) {
                    state = TaskState.DONE;
                    return;
                }

                // Check active chain
                if (activeChain != null) {
                    waitTicks++;
                    if (waitTicks > RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20) {
                        activeChain.abort("Batch timeout");
                        activeChain = null;
                        player.displayClientMessage(
                                Component.translatable("rsi.batch.failed",
                                        completedCount,
                                        Component.translatable("rsi.batch.error.timeout")),
                                false);
                        state = TaskState.FAILED;
                        return;
                    }
                    if (activeChain.isDone()) {
                        if (activeChain.isAborted()) {
                            player.displayClientMessage(
                                    Component.translatable("rsi.batch.failed",
                                            completedCount,
                                            Component.translatable("rsi.batch.error.materials_exhausted")),
                                    false);
                            activeChain = null;
                            state = TaskState.FAILED;
                            return;
                        }
                        // Chain succeeded — result was flushed to RS by chain.finish()
                        completedCount++;
                        activeChain = null;
                        player.displayClientMessage(
                                Component.translatable("rsi.batch.progress", completedCount, totalCount), true);
                        if (completedCount >= totalCount) {
                            state = TaskState.DONE;
                            return;
                        }
                        // Start next iteration
                    } else {
                        return; // still running
                    }
                }

                // Submit new chain for next iteration
                List<CraftingResolver.ResolutionStep> stepsCopy = new ArrayList<>(resolvedSteps.size());
                for (CraftingResolver.ResolutionStep step : resolvedSteps) {
                    stepsCopy.add(step);
                }
                AsyncCraftChain chain = new AsyncCraftChain(player, network, stepsCopy);
                AsyncCraftManager.getInstance().submit(chain);
                this.activeChain = chain;
                waitTicks = 0;
                return;

            case DONE:
                player.displayClientMessage(
                        Component.translatable("rsi.batch.complete", completedCount), false);
                BatchCraftManager.getInstance().removeTask(this);
                RSIntegrationMod.LOGGER.info("[RSI-Batch] Recursive batch complete: recipe={} total={}",
                        recipeId, completedCount);
                return;

            case FAILED:
                if (activeChain != null) {
                    activeChain.abort("Batch failed");
                    activeChain = null;
                }
                BatchCraftManager.getInstance().removeTask(this);
        }
    }

    private void insertIntoRS(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        INetwork network = RSIntegration.resolveNetworkFromPlayer(player);
        if (network == null && delegate != null) {
            network = com.huanghuang.rsintegration.crafting.CraftPacketUtils.resolveNetworkForCraft(
                    player, player.serverLevel().dimension(), delegate.getMachinePos());
        }
        if (network != null) {
            ItemStack remainder = network.insertItem(stack, stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!remainder.isEmpty()) {
                ItemHandlerHelper.giveItemToPlayer(player, remainder);
            }
        } else {
            ItemHandlerHelper.giveItemToPlayer(player, stack);
        }
    }
}
