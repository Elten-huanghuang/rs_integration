package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.network.ProtectionChecker;
import com.huanghuang.rsintegration.util.Diagnostics;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates execution of a crafting chain that may contain both vanilla
 * (instant) and multi-block (async) steps.
 *
 * <p>Called every server tick by {@link AsyncCraftManager}. Each tick advances
 * the chain: vanilla steps are executed in batches inline, multi-block steps
 * start and then poll for completion.</p>
 *
 * <p>Uses UUID player identification with dynamic lookup to prevent crashes
 * when a player disconnects mid-craft. All player interactions are
 * null-guarded via {@link #resolvePlayer()}.</p>
 */
public final class AsyncCraftChain {

    public enum State {
        PENDING,        // Created, not yet started
        EXECUTING,      // Running vanilla or mod steps
        WAITING_MOD,    // Waiting for multi-block craft to complete
        COMPLETING,     // Final commit + flush in progress
        COMPLETED,      // Successfully finished
        ABORTED         // Failed
    }

    private final UUID playerId;
    @Nullable private final MinecraftServer server;
    private final INetwork network;
    private final List<CraftingResolver.ResolutionStep> steps;
    private final List<ItemStack> virtualInventory = new ArrayList<>();
    private final ExtractionLedger ledger = new ExtractionLedger();

    private int currentStepIdx;
    @Nullable private IBatchDelegate currentDelegate;
    private int waitTicks;
    private State state = State.PENDING;
    private String abortReason = "";
    @Nullable private Runnable onDoneCallback;
    private int dropsThisChain;
    private boolean dropThrottleTripped;
    private static final int MAX_DROPS_PER_CHAIN = 20;

    public AsyncCraftChain(UUID playerId, MinecraftServer server, INetwork network,
                           List<CraftingResolver.ResolutionStep> steps) {
        this.playerId = playerId;
        this.server = server;
        this.network = network;
        this.steps = steps;
        RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain] Created for {}: {} steps",
                playerId, steps.size());
        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder("[RSI-AsyncChain] Steps: [");
                for (int i = 0; i < steps.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(steps.get(i).recipeId());
                }
                sb.append("]");
                RSIntegrationMod.LOGGER.debug(sb.toString());
        }
    }

    // ── player resolution ──────────────────────────────────────────

    /**
     * Look up the player by UUID. Returns null if the player is offline
     * or the server reference is unavailable.
     */
    @Nullable
    private ServerPlayer resolvePlayer() {
        if (server == null) return null;
        return server.getPlayerList().getPlayer(playerId);
    }

    // ── tick ────────────────────────────────────────────────────

    /**
     * Advance the chain by one tick. Returns true when the chain is done
     * (either finished successfully or aborted).
     */
    public boolean tick() {
        if (state == State.ABORTED || state == State.COMPLETED) return true;

        // First tick transition
        if (state == State.PENDING) {
            RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain] PENDING → EXECUTING");
            state = State.EXECUTING;
            Diagnostics.record(Diagnostics.Category.CHAIN_STATE, "PENDING→EXECUTING steps=" + steps.size());
        }

        // Dynamic player lookup — null means player disconnected
        ServerPlayer online = resolvePlayer();
        if (online == null) {
            abortSilently("Player disconnected");
            return true;
        }

        // Re-validate network each tick — RS controller may have been removed
        if (network != null && !network.canRun()) {
            abortSilently("RS controller removed or network invalidated");
            return true;
        }

        // Waiting on an async multi-block craft
        if (currentDelegate != null) {
            waitTicks++;
            int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
            if (waitTicks > timeoutTicks) {
                abort("Timeout waiting for craft completion");
                return true;
            }
            try {
                if (currentDelegate.isCraftComplete(online.serverLevel())) {
                    ItemStack result = currentDelegate.collectResult(online);
                    if (!result.isEmpty()) {
                        addToVirtualInventory(result);
                    }
                    // Add secondary byproducts from multi-block recipes
                    ServerLevel overworld = server.overworld();
                    if (overworld == null) return true;
                    Recipe<?> mbRecipe = overworld.getRecipeManager()
                            .byKey(steps.get(currentStepIdx).recipeId()).orElse(null);
                    if (mbRecipe != null) {
                        for (ItemStack secondary : com.huanghuang.rsintegration.recipe.ModRecipeHandlers.tryGetSecondaryOutputs(mbRecipe, overworld.registryAccess())) {
                            addToVirtualInventory(secondary);
                        }
                    }
                    try {
                        currentDelegate.onBatchFinished(online);
                    } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] onBatchFinished error", fe);
                    }
                    currentDelegate = null;
                    waitTicks = 0;
                    // Ledger was committed before placement in startModStep.
                    // Reset for the next step.
                    ledger.reset();
                    currentStepIdx++;
                    state = State.EXECUTING;
                    RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain] WAITING_MOD → EXECUTING");
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] Error polling craft completion", e);
                abort("Internal error during craft polling");
                return true;
            }
            return false; // still waiting
        }

        // All done
        if (currentStepIdx >= steps.size()) {
            state = State.COMPLETING;
            finish(online);
            return true;
        }

        // Execute next step(s)
        CraftingResolver.ResolutionStep step = steps.get(currentStepIdx);
        if (step.modType() == ModType.GENERIC) {
            currentStepIdx = executeVanillaBatch(currentStepIdx, online);
            if (state == State.ABORTED) return true;
            if (!ledger.isCommitted() && !ledger.commit(network, online)) {
                abort("Commit failed after vanilla batch");
                return true;
            }
            ledger.reset();
        } else {
            currentDelegate = startModStep(step, online);
            if (currentDelegate == null) {
                abort("Failed to start multi-block craft: " + step.recipeId());
                return true;
            }
            state = State.WAITING_MOD;
            Diagnostics.record(Diagnostics.Category.CHAIN_STATE,
                    "EXECUTING→WAITING_MOD step=" + step.recipeId(),
                    step.recipeId(), step.modType());
            RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain] EXECUTING → WAITING_MOD for step {}",
                    step.recipeId());
            waitTicks = 0;
        }
        return false;
    }

    public boolean isDone() { return state == State.COMPLETED || state == State.ABORTED; }
    public boolean isAborted() { return state == State.ABORTED; }
    public State state() { return state; }
    public String abortReason() { return abortReason; }
    /** @return the player's UUID (migration from stale ServerPlayer reference) */
    public UUID getPlayerId() { return playerId; }
    public int currentStep() { return currentStepIdx; }
    public int stepsCount() { return steps.size(); }
    public ExtractionLedger ledger() { return ledger; }
    public List<ItemStack> virtualInventory() { return virtualInventory; }

    public boolean belongsTo(UUID playerId) {
        return this.playerId.equals(playerId);
    }

    // ── vanilla batch execution ──────────────────────────────────

    /**
     * Execute consecutive vanilla steps synchronously in one tick.
     * Returns the index of the first non-vanilla step (or steps.size()).
     */
    private int executeVanillaBatch(int startIdx, ServerPlayer online) {
        List<ResourceLocation> vanillaIds = new ArrayList<>();
        int i = startIdx;
        while (i < steps.size() && steps.get(i).modType() == ModType.GENERIC) {
            vanillaIds.add(steps.get(i).recipeId());
            i++;
        }

        if (!vanillaIds.isEmpty()) {
            executeVanillaStepsInline(vanillaIds, online);
        }
        return i;
    }

    /**
     * Execute vanilla crafting steps inline, using the chain's virtual inventory
     * and ledger so intermediate outputs feed forward across the entire chain.
     */
    private boolean executeVanillaStepsInline(List<ResourceLocation> stepIds, ServerPlayer online) {
        ServerLevel overworld = server.overworld();
        if (overworld == null) return false;
        RecipeManager rm = overworld.getRecipeManager();
        RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain] executeVanillaStepsInline: {} vanilla steps, currentStepIdx={}",
                stepIds.size(), currentStepIdx);
        logVirtualInventory("before batch");

        for (ResourceLocation stepId : stepIds) {
            Recipe<?> recipe = rm.byKey(stepId).orElse(null);
            if (recipe == null) {
                RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain]   step {} not found in recipe manager", stepId);
                continue;
            }

            RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain]   processing step: {}", stepId);

            if (recipe instanceof net.minecraft.world.item.crafting.CraftingRecipe cr) {
                // Track only the slots actually modified so we can roll back
                // just those slots on failure — avoids full-inventory snapshot copies.
                Map<Integer, ItemStack> modifiedSlots = new HashMap<>();

                for (Ingredient ing : cr.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    int stillNeeded = 1;
                    for (int i = 0; i < virtualInventory.size() && stillNeeded > 0; i++) {
                        ItemStack vi = virtualInventory.get(i);
                        if (vi.isEmpty()) continue;
                        if (ing.test(vi)) {
                            modifiedSlots.putIfAbsent(i, vi.copy());
                            int take = Math.min(stillNeeded, vi.getCount());
                            vi.shrink(take);
                            stillNeeded -= take;
                        }
                    }
                    if (stillNeeded > 0) {
                        ItemStack reserved = ledger.reserveFromNetwork(ing, stillNeeded, network);
                        if (reserved.isEmpty()) {
                            reserved = ledger.reserveFromInventory(ing, stillNeeded, online);
                        }
                        if (reserved.isEmpty()) {
                            modifiedSlots.forEach((idx, originalStack) -> {
                                if (idx < virtualInventory.size()) {
                                    virtualInventory.set(idx, originalStack);
                                } else {
                                    virtualInventory.add(originalStack);
                                }
                            });
                            logMissingIngredient(ing, stepId);
                            logVirtualInventory("at failure for step " + stepId);
                            logLedgerState();
                            abort("Missing: " + describeIngredientSafe(ing));
                            return false;
                        }
                    }
                }

                ItemStack result = cr.getResultItem(server.overworld().registryAccess());
                if (!result.isEmpty()) {
                    addToVirtualInventory(result);
                }
                for (ItemStack secondary : com.huanghuang.rsintegration.recipe.ModRecipeHandlers.tryGetSecondaryOutputs(cr, server.overworld().registryAccess())) {
                    addToVirtualInventory(secondary);
                }
                for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(cr)) {
                    addToVirtualInventory(remainder);
                }
            } else {
                // Non-crafting GENERIC recipe (e.g. sawmill, custom mod type)
                List<com.huanghuang.rsintegration.crafting.IngredientSpec> specs =
                        CraftPacketUtils.extractIngredientSpecs(recipe);
                if (specs == null || specs.isEmpty()) continue;

                for (com.huanghuang.rsintegration.crafting.IngredientSpec spec : specs) {
                    if (spec.isEmpty()) continue;
                    int stillNeeded = spec.count();
                    var iter = virtualInventory.iterator();
                    while (iter.hasNext() && stillNeeded > 0) {
                        ItemStack vi = iter.next();
                        if (spec.ingredient().test(vi)) {
                            int take = Math.min(stillNeeded, vi.getCount());
                            vi.shrink(take);
                            stillNeeded -= take;
                            if (vi.isEmpty()) iter.remove();
                        }
                    }
                    if (stillNeeded > 0) {
                        ItemStack reserved = ledger.reserveFromNetwork(
                                spec.ingredient(), stillNeeded, network);
                        if (reserved.isEmpty()) {
                            reserved = ledger.reserveFromInventory(
                                    spec.ingredient(), stillNeeded, online);
                        }
                        if (reserved.isEmpty()) {
                            logMissingIngredient(spec.ingredient(), stepId);
                            logVirtualInventory("at failure for step " + stepId);
                            logLedgerState();
                            abort("Missing: " + describeIngredientSafe(spec.ingredient()));
                            return false;
                        }
                    }
                }

                ItemStack result = com.huanghuang.rsintegration.recipe.ModRecipeHandlers.tryGetResultItem(
                        recipe, server.overworld().registryAccess());
                if (!result.isEmpty()) {
                    addToVirtualInventory(result);
                }
                for (ItemStack secondary : com.huanghuang.rsintegration.recipe.ModRecipeHandlers.tryGetSecondaryOutputs(recipe, server.overworld().registryAccess())) {
                    addToVirtualInventory(secondary);
                }
                for (IngredientSpec spec : specs) {
                    if (spec.isEmpty()) continue;
                    for (ItemStack stack : spec.ingredient().getItems()) {
                        if (stack.isEmpty()) continue;
                        try {
                            ItemStack remainder = stack.getCraftingRemainingItem();
                            if (!remainder.isEmpty()) {
                                addToVirtualInventory(remainder.copyWithCount(spec.count()));
                                break;
                            }
                        } catch (Exception e) {
                            RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain] getCraftingRemainingItem failed", e);
                        }
                    }
                }
            }
        }
        return true;
    }

    // ── multi-block step execution ───────────────────────────────

    @Nullable
    private IBatchDelegate startModStep(CraftingResolver.ResolutionStep step, ServerPlayer online) {
        // Extract machine sub-type from recipe ID (e.g. "wissen_crystallizer"
        // from "wizards_reborn:wissen_crystallizer/earth_crystal_seed") so we
        // only probe machines of the correct type, not every binding for the mod.
        String path = step.recipeId().getPath();
        int slash = path.indexOf('/');
        String subTypeHint = slash > 0 ? path.substring(0, slash).toLowerCase() : null;

        List<BoundMachine> machines = AltarBindingRegistry.getBoundMachinesForType(
                online, step.modType(), subTypeHint);
        if (machines.isEmpty()) {
            // Diagnostic: also check how many bindings exist for this mod type
            // (without sub-type filter) so we can tell if sub-type mismatch or
            // no binding at all.
            int totalForMod = AltarBindingRegistry.getBoundMachinesForType(
                    online, step.modType()).size();
            RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] No bound machine for mod type {} subType={} (total {} bindings for this mod)",
                    step.modType(), subTypeHint != null ? subTypeHint : "*", totalForMod);
            if (totalForMod > 0) {
                // Machines of this mod ARE bound, but none match the sub-type filter
                online.sendSystemMessage(Component.translatable(
                        "rsi.async.error.wrong_machine_type", step.recipeId(), machines.size()));
            } else {
                online.sendSystemMessage(Component.translatable(
                        "rsi.async.error.no_machine_bound", step.recipeId()));
            }
            return null;
        }

        IBatchDelegate delegate = step.inferMode()
                ? step.modType().createInferDelegate()
                : createDelegate(step.modType());
        if (delegate == null) return null;

        // Try each bound machine until one validates successfully.
        BoundMachine matchedMachine = null;
        for (BoundMachine m : machines) {
            try {
                if (delegate.validateAndInit(online, step.recipeId(), m.dim(), m.pos())) {
                    matchedMachine = m;
                    break;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain] validateAndInit failed for machine at {}: {}",
                        m.pos(), e.toString());
            }
        }
        if (matchedMachine == null) {
            // Machines were found but validateAndInit failed on every one.
            // The delegate already printed a specific error (pedestal, energy, etc.).
            RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] All {} bound machines failed validateAndInit for mod type {}: recipe={}",
                    machines.size(), step.modType(), step.recipeId());
            online.sendSystemMessage(Component.translatable(
                    "rsi.async.error.machine_valid_failed", step.recipeId()));
            return null;
        }

        // Protection check
        try {
            var dimKey = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, matchedMachine.dim());
            ServerLevel machineLevel = server.getLevel(dimKey);
            if (machineLevel != null
                    && !ProtectionChecker.canInteract(online, machineLevel, matchedMachine.pos())) {
                online.sendSystemMessage(Component.translatable("rsi.error.protection_denied"));
                return null;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain] Protection check failed: {}", e.toString());
        }

        try {
            List<IngredientSpec> specs = delegate.getRequiredMaterials();
            if (specs != null && !specs.isEmpty()) {
                List<ItemStack> materials = preReserveStepMaterials(specs, online);
                if (materials == null) {
                    RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Failed to pre-reserve materials for {}",
                            step.recipeId());
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials", step.recipeId()));
                    try { delegate.onBatchFailed(online, "pre-reserve failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] onBatchFailed threw during pre-reserve cleanup", fe);
}
                    return null;
                }
                if (!ledger.commit(network, online)) {
                    RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Ledger commit failed for {}",
                            step.recipeId());
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials", step.recipeId()));
                    try { delegate.onBatchFailed(online, "commit failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] onBatchFailed threw during commit cleanup", fe);
}
                    return null;
                }
                if (!delegate.tryStartWithMaterials(online, materials, ledger)) {
                    RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Delegate tryStartWithMaterials failed for {}",
                            step.recipeId());
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.craft_failed", step.recipeId()));
                    try { delegate.onBatchFailed(online, "tryStartWithMaterials failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] onBatchFailed threw during tryStartWithMaterials cleanup", fe);
}
                    ledger.refundCommitted(network, online);
                    return null;
                }
            } else {
                if (!delegate.tryStartSingleCraft(online, ledger)) {
                    RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Delegate tryStartSingleCraft failed for {}",
                            step.recipeId());
                    try { delegate.onBatchFailed(online, "tryStartSingleCraft failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] onBatchFailed threw during tryStartSingleCraft cleanup", fe);
}
                    return null;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] Error starting multi-block step", e);
            try { delegate.onBatchFailed(online, "exception in startModStep"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] onBatchFailed threw during exception cleanup", fe);
}
            return null;
        }

        RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain] Multi-block step started OK: recipe={} delegate={}",
                step.recipeId(), delegate.getClass().getSimpleName());
        return delegate;
    }

    @Nullable
    private List<ItemStack> preReserveStepMaterials(List<IngredientSpec> specs, ServerPlayer online) {
        List<ItemStack> materials = new ArrayList<>();
        List<ItemStack> virtualSnapshot = new ArrayList<>();
        for (ItemStack vi : virtualInventory) {
            virtualSnapshot.add(vi.copy());
        }
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) {
                materials.add(ItemStack.EMPTY);
                continue;
            }
            int needed = spec.count();
            ItemStack material = ItemStack.EMPTY;

            var iter = virtualInventory.iterator();
            while (iter.hasNext() && needed > 0) {
                ItemStack vi = iter.next();
                if (spec.ingredient().test(vi)) {
                    int take = Math.min(needed, vi.getCount());
                    ItemStack taken = vi.split(take);
                    if (vi.isEmpty()) iter.remove();
                    if (material.isEmpty()) {
                        material = taken;
                    } else {
                        material.grow(take);
                    }
                    needed -= take;
                }
            }

            if (needed > 0 && network != null) {
                ItemStack reserved = ledger.reserveFromNetwork(spec.ingredient(), needed, network);
                if (!reserved.isEmpty()) {
                    if (material.isEmpty()) {
                        material = reserved;
                    } else {
                        material.grow(reserved.getCount());
                    }
                    needed = 0;
                }
            }
            if (needed > 0) {
                ItemStack reserved = ledger.reserveFromInventory(spec.ingredient(), needed, online);
                if (!reserved.isEmpty()) {
                    if (material.isEmpty()) {
                        material = reserved;
                    } else {
                        material.grow(reserved.getCount());
                    }
                    needed = 0;
                }
            }

            if (needed > 0) {
                if (!material.isEmpty()) {
                    ledger.releaseReservations(List.of(material));
                }
                ledger.releaseReservations(materials);
                virtualInventory.clear();
                virtualInventory.addAll(virtualSnapshot);
                RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] preReserveStepMaterials failed: need {} more of ingredient for step {}",
                        needed, steps.get(currentStepIdx).recipeId());
                return null;
            }
            materials.add(material);
        }
        return materials;
    }

    // ── virtual inventory ────────────────────────────────────────

    private void addToVirtualInventory(ItemStack stack) {
        if (stack.isEmpty()) return;
        for (ItemStack vi : virtualInventory) {
            if (ItemStack.isSameItemSameTags(vi, stack)) {
                vi.grow(stack.getCount());
                return;
            }
        }
        virtualInventory.add(stack.copy());
    }

    private void flushVirtualInventory(@Nullable ServerPlayer online) {
        if (network == null) return;
        var iter = virtualInventory.iterator();
        while (iter.hasNext()) {
            ItemStack vi = iter.next();
            if (!vi.isEmpty()) {
                if (online != null) {
                    var tracker = network.getItemStorageTracker();
                    if (tracker != null) tracker.changed(online, vi.copy());
                }
                ItemStack leftover = network.insertItem(vi.copy(), vi.getCount(),
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                if (online != null && !leftover.isEmpty()) {
                    safeGiveToPlayer(online, leftover);
                } else if (online == null && !leftover.isEmpty()) {
                    insertOrDropAtSpawn(leftover);
                }
                iter.remove();
            }
        }
    }

    private void insertOrDropAtSpawn(ItemStack stack) {
        if (network != null) {
            ItemStack stillLeft = network.insertItem(stack.copy(), stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (stillLeft.isEmpty()) return;
            stack = stillLeft;
        }
        if (dropThrottleTripped) {
            RSIntegrationMod.LOGGER.warn("[RSI] Drop throttle tripped — discarding {} x{} for player {}",
                    stack.getHoverName().getString(), stack.getCount(), playerId);
            return;
        }
        dropsThisChain++;
        if (dropsThisChain > MAX_DROPS_PER_CHAIN) {
            dropThrottleTripped = true;
            RSIntegrationMod.LOGGER.warn("[RSI] Drop throttle tripped ({} drops) — discarding {} x{} and all future drops for player {}",
                    MAX_DROPS_PER_CHAIN, stack.getHoverName().getString(), stack.getCount(), playerId);
            return;
        }
        if (server != null) {
            var spawnLevel = server.overworld();
            if (spawnLevel == null) return;
            var spawnPos = spawnLevel.getSharedSpawnPos();
            spawnLevel.addFreshEntity(
                new ItemEntity(spawnLevel,
                    spawnPos.getX() + 0.5, spawnPos.getY() + 0.5, spawnPos.getZ() + 0.5, stack.copy()));
            RSIntegrationMod.LOGGER.warn("[RSI] Item dropped at world spawn (player {} offline): {} x{}",
                playerId, stack.getHoverName().getString(), stack.getCount());
        }
    }

    // ── lifecycle ────────────────────────────────────────────────

    private void finish(ServerPlayer online) {
        if (!ledger.commit(network, online)) {
            RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Commit failed for player {} after {} steps",
                    online.getName().getString(), steps.size());
            online.sendSystemMessage(Component.translatable("rsi.async.error.commit_failed"));
            abort("Final commit failed");
            return;
        }

        if (network != null) {
            for (ItemStack vi : virtualInventory) {
                if (!vi.isEmpty()) {
                    var tracker = network.getItemStorageTracker();
                    if (tracker != null) tracker.changed(online, vi.copy());
                    ItemStack leftover = network.insertItem(vi.copy(), vi.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    if (!leftover.isEmpty()) {
                        safeGiveToPlayer(online, leftover);
                    }
                }
            }
        }

        state = State.COMPLETED;
        Diagnostics.record(Diagnostics.Category.CHAIN_STATE, "→COMPLETED steps=" + steps.size());
        RSIntegrationMod.LOGGER.info("[RSI-AsyncChain] COMPLETED for player {}: {} steps",
                online.getName().getString(), steps.size());
        fireOnDone();
    }

    private void fireOnDone() {
        if (onDoneCallback != null) {
            try { onDoneCallback.run(); } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] onDone callback threw", e);
            }
        }
    }

    public void onDone(@Nullable Runnable callback) {
        this.onDoneCallback = callback;
    }

    public void abort(String reason) {
        if (state == State.ABORTED || state == State.COMPLETED) return;

        ServerPlayer online = resolvePlayer();
        RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Aborting chain (state={}) for {}: {}",
                state, online != null ? online.getName().getString() : playerId, reason);
        Diagnostics.record(Diagnostics.Category.CHAIN_STATE,
                "→ABORTED reason=" + reason + " atStep=" + currentStepIdx + "/" + steps.size());
        state = State.ABORTED;
        abortReason = reason;

        // Delegate cleanup — guard with null check for offline player
        if (currentDelegate != null && online != null) {
            try {
                currentDelegate.onBatchFailed(online, reason);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] Error in onBatchFailed", e);
            }
            currentDelegate = null;
        } else if (currentDelegate != null) {
            RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Skipping delegate cleanup (player offline): delegate={}",
                    currentDelegate.getClass().getSimpleName());
            currentDelegate = null;
        }

        // Refund ledger: require live player for inventory operations
        if (online != null) {
            if (ledger.isCommitted()) {
                ledger.refundCommitted(network, online);
            } else {
                ledger.rollback(online);
            }
        }

        flushVirtualInventory(online);
        virtualInventory.clear();

        // Only send message if player is still online
        if (online != null) {
            online.sendSystemMessage(Component.translatable("rsi.async.chain_aborted", reason));
        }
        fireOnDone();
    }

    private void abortSilently(String reason) {
        if (state == State.ABORTED || state == State.COMPLETED) return;

        RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Aborting silently (state={}) for {}: {}",
                state, playerId, reason);
        Diagnostics.record(Diagnostics.Category.CHAIN_STATE,
                "→ABORTED_SILENT reason=" + reason + " atStep=" + currentStepIdx + "/" + steps.size());
        state = State.ABORTED;
        abortReason = reason;

        if (currentDelegate != null) {
            RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Skipping delegate cleanup (player offline): delegate={}",
                    currentDelegate.getClass().getSimpleName());
            currentDelegate = null;
        }

        flushVirtualInventory(null);
        virtualInventory.clear();

        fireOnDone();
    }

    // ── delegate factory ─────────────────────────────────────────

    @Nullable
    private static IBatchDelegate createDelegate(ModType type) {
        if (type == ModType.GENERIC) return null;
        return type.createDelegate();
    }

    // ── safe item give ───────────────────────────────────────────

    private void safeGiveToPlayer(ServerPlayer player, ItemStack stack) {
        com.huanghuang.rsintegration.util.PlayerUtils.safeGiveToPlayer(player, stack, network);
    }

    // ── debug helpers ────────────────────────────────────────────

    private static String describeIngredientSafe(Ingredient ing) {
        for (ItemStack stack : ing.getItems()) {
            if (!stack.isEmpty()) return stack.getHoverName().getString();
        }
        return "Unknown";
    }

    private static void logMissingIngredient(Ingredient ing, ResourceLocation stepId) {
        StringBuilder sb = new StringBuilder("[RSI-AsyncChain] Missing ingredient for step ");
        sb.append(stepId).append(" — options: ");
        for (ItemStack stack : ing.getItems()) {
            if (!stack.isEmpty()) {
                net.minecraft.resources.ResourceLocation rl =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (rl != null) sb.append(rl).append(" ");
            }
        }
        RSIntegrationMod.LOGGER.debug(sb.toString());
    }

    private void logVirtualInventory(String context) {
        if (!RSIntegrationMod.LOGGER.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder("[RSI-AsyncChain] VirtualInventory ");
        sb.append(context).append(" (size=").append(virtualInventory.size()).append("):");
        for (ItemStack vi : virtualInventory) {
            if (vi.isEmpty()) continue;
            net.minecraft.resources.ResourceLocation rl =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(vi.getItem());
            sb.append(" [").append(rl).append(" x").append(vi.getCount());
            if (vi.hasTag()) sb.append(" +nbt");
            sb.append("]");
        }
        RSIntegrationMod.LOGGER.debug(sb.toString());
    }

    private void logLedgerState() {
        if (!RSIntegrationMod.LOGGER.isDebugEnabled()) return;
        RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain] Ledger entries={} committed={}",
                ledger.size(), ledger.isCommitted());
        RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain] Network available (sample): {}",
                ledger.describePending());
    }
}
