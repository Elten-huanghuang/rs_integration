package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.util.Diagnostics;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates execution of a crafting chain that may contain both vanilla
 * (instant) and multi-block (async) steps.
 *
 * <p>Called every server tick by {@link AsyncCraftManager}. Each tick advances
 * the chain: vanilla steps are executed in batches inline, multi-block steps
 * start and then poll for completion.</p>
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

    private final ServerPlayer player;
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

    public AsyncCraftChain(ServerPlayer player, INetwork network,
                           List<CraftingResolver.ResolutionStep> steps) {
        this.player = player;
        this.network = network;
        this.steps = steps;
        RSIntegrationMod.LOGGER.info("[RSI-AsyncChain] Created for {}: {} steps",
                player.getName().getString(), steps.size());
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

        if (player.isRemoved()) {
            abort("Player disconnected");
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
                if (currentDelegate.isCraftComplete(player.serverLevel())) {
                    ItemStack result = currentDelegate.collectResult(player);
                    if (!result.isEmpty()) {
                        addToVirtualInventory(result);
                    }
                    // Add secondary byproducts from multi-block recipes
                    Recipe<?> mbRecipe = player.serverLevel().getRecipeManager()
                            .byKey(steps.get(currentStepIdx).recipeId()).orElse(null);
                    if (mbRecipe != null) {
                        for (ItemStack secondary : com.huanghuang.rsintegration.recipe.ModRecipeHandlers.tryGetSecondaryOutputs(mbRecipe, player.serverLevel().registryAccess())) {
                            addToVirtualInventory(secondary);
                        }
                    }
                    try {
                        currentDelegate.onBatchFinished(player);
                    } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] onBatchFinished error", fe);
                    }
                    currentDelegate = null;
                    waitTicks = 0;
                    // Commit this step's extractions now so the next step
                    // starts with a clean ledger (no stale pending entries
                    // that reduce availability for subsequent reservations).
                    if (!ledger.commit(network, player)) {
                        abort("Commit failed after step completion");
                        return true;
                    }
                    ledger.reset();
                    currentStepIdx++;
                    state = State.EXECUTING;
                    RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain] WAITING_MOD → EXECUTING");
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] Error polling craft completion", e);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                abort("Craft polling error: " + msg);
                return true;
            }
            return false; // still waiting
        }

        // All done
        if (currentStepIdx >= steps.size()) {
            state = State.COMPLETING;
            finish();
            return true;
        }

        // Execute next step(s)
        CraftingResolver.ResolutionStep step = steps.get(currentStepIdx);
        if (step.modType() == ModType.GENERIC) {
            currentStepIdx = executeVanillaBatch(currentStepIdx);
            if (state == State.ABORTED) return true;
            if (!ledger.isCommitted() && !ledger.commit(network, player)) {
                abort("Commit failed after vanilla batch");
                return true;
            }
            ledger.reset();
        } else {
            currentDelegate = startModStep(step);
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
    public ServerPlayer getPlayer() { return player; }
    public int currentStep() { return currentStepIdx; }
    public int stepsCount() { return steps.size(); }
    public ExtractionLedger ledger() { return ledger; }
    public List<ItemStack> virtualInventory() { return virtualInventory; }

    public boolean belongsTo(java.util.UUID playerId) {
        return player.getUUID().equals(playerId);
    }

    // ── vanilla batch execution ──────────────────────────────────

    /**
     * Execute consecutive vanilla steps synchronously in one tick.
     * Returns the index of the first non-vanilla step (or steps.size()).
     */
    private int executeVanillaBatch(int startIdx) {
        List<ResourceLocation> vanillaIds = new ArrayList<>();
        int i = startIdx;
        while (i < steps.size() && steps.get(i).modType() == ModType.GENERIC) {
            vanillaIds.add(steps.get(i).recipeId());
            i++;
        }

        if (!vanillaIds.isEmpty()) {
            executeVanillaStepsInline(vanillaIds);
        }
        return i;
    }

    /**
     * Execute vanilla crafting steps inline, using the chain's virtual inventory
     * and ledger so intermediate outputs feed forward across the entire chain.
     */
    private boolean executeVanillaStepsInline(List<ResourceLocation> stepIds) {
        RecipeManager rm = player.serverLevel().getRecipeManager();
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
                // Snapshot virtual inventory before consuming — if this step fails
                // we must restore it so intermediate products from prior committed
                // steps survive the abort and are flushed to RS.
                List<ItemStack> viSnapshot = new ArrayList<>(virtualInventory.size());
                for (ItemStack vi : virtualInventory) viSnapshot.add(vi.copy());

                for (Ingredient ing : cr.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    int stillNeeded = 1;
                    var iter = virtualInventory.iterator();
                    while (iter.hasNext() && stillNeeded > 0) {
                        ItemStack vi = iter.next();
                        if (ing.test(vi)) {
                            int take = Math.min(stillNeeded, vi.getCount());
                            vi.shrink(take);
                            stillNeeded -= take;
                            if (vi.isEmpty()) iter.remove();
                        }
                    }
                    if (stillNeeded > 0) {
                        ItemStack reserved = ledger.reserveFromNetwork(ing, stillNeeded, network);
                        if (reserved.isEmpty()) {
                            reserved = ledger.reserveFromInventory(ing, stillNeeded, player);
                        }
                        if (reserved.isEmpty()) {
                            logMissingIngredient(ing, stepId);
                            logVirtualInventory("at failure for step " + stepId);
                            logLedgerState();
                            // Restore virtual inventory snapshot so prior-step
                            // outputs survive the abort flush.
                            virtualInventory.clear();
                            virtualInventory.addAll(viSnapshot);
                            abort("Missing: " + describeIngredientSafe(ing));
                            return false;
                        }
                    }
                }

                ItemStack result = cr.getResultItem(player.serverLevel().registryAccess());
                if (!result.isEmpty()) {
                    addToVirtualInventory(result);
                }
                for (ItemStack secondary : com.huanghuang.rsintegration.recipe.ModRecipeHandlers.tryGetSecondaryOutputs(cr, player.serverLevel().registryAccess())) {
                    addToVirtualInventory(secondary);
                }
                // Handle crafting remainders (e.g. empty buckets from cake recipe)
                for (Ingredient ing : cr.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    for (ItemStack stack : ing.getItems()) {
                        if (stack.isEmpty()) continue;
                        try {
                            ItemStack remainder = stack.getCraftingRemainingItem();
                            if (!remainder.isEmpty() && !ItemStack.isSameItem(stack, remainder)) {
                                addToVirtualInventory(remainder.copyWithCount(1));
                                break;
                            }
                        } catch (Throwable e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                    }
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
                                    spec.ingredient(), stillNeeded, player);
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
                        recipe, player.serverLevel().registryAccess());
                if (!result.isEmpty()) {
                    addToVirtualInventory(result);
                }
                for (ItemStack secondary : com.huanghuang.rsintegration.recipe.ModRecipeHandlers.tryGetSecondaryOutputs(recipe, player.serverLevel().registryAccess())) {
                    addToVirtualInventory(secondary);
                }
            }
        }
        return true;
    }

    // ── multi-block step execution ───────────────────────────────

    @Nullable
    private IBatchDelegate startModStep(CraftingResolver.ResolutionStep step) {
        // Extract machine sub-type from recipe ID (e.g. "wissen_crystallizer"
        // from "wizards_reborn:wissen_crystallizer/earth_crystal_seed") so we
        // only probe machines of the correct type, not every binding for the mod.
        String path = step.recipeId().getPath();
        int slash = path.indexOf('/');
        String subTypeHint = slash > 0 ? path.substring(0, slash).toLowerCase() : null;

        List<BoundMachine> machines = AltarBindingRegistry.getBoundMachinesForType(
                player, step.modType(), subTypeHint);
        if (machines.isEmpty()) {
            // Diagnostic: also check how many bindings exist for this mod type
            // (without sub-type filter) so we can tell if sub-type mismatch or
            // no binding at all.
            int totalForMod = AltarBindingRegistry.getBoundMachinesForType(
                    player, step.modType()).size();
            RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] No bound machine for mod type {} subType={} (total {} bindings for this mod)",
                    step.modType(), subTypeHint != null ? subTypeHint : "*", totalForMod);
            player.sendSystemMessage(Component.translatable(
                    "rsi.async.error.wrong_machine_type", step.recipeId(), 0));
            return null;
        }

        IBatchDelegate delegate = createDelegate(step.modType());
        if (delegate == null) return null;

        // Try each bound machine until one validates successfully.
        // This handles mods with multiple machine sub-types (e.g. WR has
        // crystallizer, workbench, iterator, and crystal ritual — all share
        // WIZARDS_REBORN mod type but each recipe only works on one machine).
        BoundMachine matchedMachine = null;
        for (BoundMachine m : machines) {
            try {
                if (delegate.validateAndInit(player, step.recipeId(), m.dim(), m.pos())) {
                    matchedMachine = m;
                    break;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain] validateAndInit failed for machine at {}: {}",
                        m.pos(), e.toString());
            }
        }
        if (matchedMachine == null) {
            // validateAndInit already sent a specific player-facing message
            // (e.g. "crystal ritual has no crystal", "machine not empty").
            // Don't overwrite it with a generic "wrong machine type" message.
            RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] No compatible machine among {} bound for mod type {}: recipe={}",
                    machines.size(), step.modType(), step.recipeId());
            return null;
        }

        try {
            // Pre-reserve materials from chain's virtual inventory + master ledger,
            // then pass the pre-extracted stacks to the delegate so all multi-block
            // steps in the chain share the same ledger (fixes resource exhaustion
            // when the same machine is called 2+ times).
            List<IngredientSpec> specs = delegate.getRequiredMaterials();
            if (specs != null && !specs.isEmpty()) {
                List<ItemStack> materials = preReserveStepMaterials(specs);
                if (materials == null) {
                    RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Failed to pre-reserve materials for {}",
                            step.recipeId());
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials", step.recipeId()));
                    try { delegate.onBatchFailed(player, "pre-reserve failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] onBatchFailed threw during pre-reserve cleanup", fe);
}
                    return null;
                }
                if (!delegate.tryStartWithMaterials(player, materials, ledger)) {
                    RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Delegate tryStartWithMaterials failed for {}",
                            step.recipeId());
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.craft_failed", step.recipeId()));
                    try { delegate.onBatchFailed(player, "tryStartWithMaterials failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] onBatchFailed threw during tryStartWithMaterials cleanup", fe);
}
                    ledger.releaseReservations(materials);
                    return null;
                }
            } else {
                // Fallback: delegate manages its own extraction (backward compat)
                if (!delegate.tryStartSingleCraft(player, ledger)) {
                    RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Delegate tryStartSingleCraft failed for {}",
                            step.recipeId());
                    try { delegate.onBatchFailed(player, "tryStartSingleCraft failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] onBatchFailed threw during tryStartSingleCraft cleanup", fe);
}
                    return null;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] Error starting multi-block step", e);
            try { delegate.onBatchFailed(player, "exception in startModStep"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] onBatchFailed threw during exception cleanup", fe);
}
            return null;
        }

        RSIntegrationMod.LOGGER.info("[RSI-AsyncChain] Multi-block step started OK: recipe={} delegate={}",
                step.recipeId(), delegate.getClass().getSimpleName());
        return delegate;
    }

    /**
     * Pre-reserve materials for a multi-block step from virtual inventory first,
     * then from the chain's master ledger. Returns a list of real ItemStacks
     * (copies from ledger reservations) that can be placed in machine slots.
     */
    @Nullable
    private List<ItemStack> preReserveStepMaterials(List<IngredientSpec> specs) {
        List<ItemStack> materials = new ArrayList<>();
        // Snapshot virtual inventory so we can restore it atomically on failure.
        // Must deep-copy: new ArrayList<>(...) only copies references, but vi.split()
        // mutates the same ItemStack objects the snapshot points to.
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

            // 1. Try virtual inventory first (intermediate outputs of prior steps)
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

            // 2. Still needed? Reserve from chain's master ledger
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
                ItemStack reserved = ledger.reserveFromInventory(spec.ingredient(), needed, player);
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
                // Release ledger reservations for current partially-built material
                // (may contain ledger templates that hold network/player items).
                if (!material.isEmpty()) {
                    ledger.releaseReservations(List.of(material));
                }
                // Release ledger reservations for previously completed materials.
                ledger.releaseReservations(materials);
                // Restore virtual inventory from snapshot — undoes all vi.split()
                // calls above so no intermediate outputs are lost.
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

    /**
     * Flush all virtual inventory items into RS. Called after each ledger
     * commit so that completed-step results survive a later abort.
     */
    private void flushVirtualInventory() {
        if (network == null) return;
        var iter = virtualInventory.iterator();
        while (iter.hasNext()) {
            ItemStack vi = iter.next();
            if (!vi.isEmpty()) {
                ItemStack leftover = network.insertItem(vi.copy(), vi.getCount(),
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                if (!leftover.isEmpty()) {
                    net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, leftover);
                }
                iter.remove();
            }
        }
    }

    // ── lifecycle ────────────────────────────────────────────────

    private void finish() {
        // Commit all real extractions atomically
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Commit failed for player {} after {} steps",
                    player.getName().getString(), steps.size());
            player.sendSystemMessage(Component.translatable("rsi.async.error.commit_failed"));
            abort("Final commit failed");
            return;
        }

        // Flush remaining virtual inventory into RS network
        if (network != null) {
            for (ItemStack vi : virtualInventory) {
                if (!vi.isEmpty()) {
                    ItemStack leftover = network.insertItem(vi.copy(), vi.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    if (!leftover.isEmpty()) {
                        net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, leftover);
                    }
                }
            }
        }

        state = State.COMPLETED;
        Diagnostics.record(Diagnostics.Category.CHAIN_STATE, "→COMPLETED steps=" + steps.size());
        RSIntegrationMod.LOGGER.info("[RSI-AsyncChain] COMPLETED for player {}: {} steps",
                player.getName().getString(), steps.size());
        fireOnDone();
    }

    private void fireOnDone() {
        if (onDoneCallback != null) {
            try { onDoneCallback.run(); } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] onDone callback threw", e);
            }
        }
    }

    /** Register a callback invoked when the chain enters a terminal state. */
    public void onDone(@Nullable Runnable callback) {
        this.onDoneCallback = callback;
    }

    public void abort(String reason) {
        if (state == State.ABORTED || state == State.COMPLETED) return;
        RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Aborting chain (state={}) for {}: {}",
                state, player.getName().getString(), reason);
        Diagnostics.record(Diagnostics.Category.CHAIN_STATE,
                "→ABORTED reason=" + reason + " atStep=" + currentStepIdx + "/" + steps.size());
        state = State.ABORTED;
        abortReason = reason;

        // Clean up active delegate (refunds items placed in machine)
        if (currentDelegate != null) {
            try {
                currentDelegate.onBatchFailed(player, reason);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] Error in onBatchFailed", e);
            }
            currentDelegate = null;
        }

        // Release any uncommitted ledger reservations — items were
        // reserved but never physically extracted from RS.
        ledger.rollback(player);

        // Flush virtual inventory — prior steps' ledgers were committed,
        // so their outputs are legitimate. Discarding would lose items
        // whose inputs were already extracted from RS.
        if (network != null) {
            for (ItemStack vi : virtualInventory) {
                if (!vi.isEmpty()) {
                    ItemStack leftover = network.insertItem(vi.copy(), vi.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    if (!leftover.isEmpty()) {
                        net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, leftover);
                    }
                }
            }
        }
        virtualInventory.clear();

        player.sendSystemMessage(Component.translatable("rsi.async.chain_aborted", reason));
        fireOnDone();
    }

    // ── delegate factory ─────────────────────────────────────────

    @Nullable
    private static IBatchDelegate createDelegate(ModType type) {
        if (type == ModType.GENERIC) return null;
        return type.createDelegate();
    }

    // ── debug helpers ────────────────────────────────────────────

    private static String describeIngredientSafe(Ingredient ing) {
        for (ItemStack stack : ing.getItems()) {
            if (!stack.isEmpty()) {
                net.minecraft.resources.ResourceLocation rl =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
                return rl != null ? rl.toString() : stack.getDisplayName().getString();
            }
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
