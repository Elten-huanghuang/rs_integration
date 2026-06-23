package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.batch.IBatchDelegate;
import com.huanghuang.rsintegration.batch.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.integration.AltarBindingRegistry;
import com.huanghuang.rsintegration.integration.AltarBindingRegistry.BoundMachine;
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

    private final ServerPlayer player;
    private final INetwork network;
    private final List<CraftingResolver.ResolutionStep> steps;
    private final List<ItemStack> virtualInventory = new ArrayList<>();
    private final ExtractionLedger ledger = new ExtractionLedger();

    private int currentStepIdx;
    @Nullable private IBatchDelegate currentDelegate;
    private int waitTicks;
    private boolean aborted;
    private String abortReason = "";

    public AsyncCraftChain(ServerPlayer player, INetwork network,
                           List<CraftingResolver.ResolutionStep> steps) {
        this.player = player;
        this.network = network;
        this.steps = steps;
        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder("[RSI-AsyncChain] Created for ");
            sb.append(player.getName().getString()).append(": ").append(steps.size()).append(" steps [");
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
        if (aborted) return true;
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
                        for (ItemStack secondary : ModRecipeIndex.tryGetSecondaryOutputs(mbRecipe, player.serverLevel().registryAccess())) {
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
                    flushVirtualInventory();
                    currentStepIdx++;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] Error polling craft completion", e);
                abort("Craft polling error: " + e.getMessage());
                return true;
            }
            return false; // still waiting
        }

        // All done
        if (currentStepIdx >= steps.size()) {
            finish();
            return true;
        }

        // Execute next step(s)
        CraftingResolver.ResolutionStep step = steps.get(currentStepIdx);
        if (step.modType() == ModType.GENERIC) {
            currentStepIdx = executeVanillaBatch(currentStepIdx);
            if (aborted) return true;
            if (!ledger.isCommitted() && !ledger.commit(network, player)) {
                abort("Commit failed after vanilla batch");
                return true;
            }
            ledger.reset();
            flushVirtualInventory();
        } else {
            currentDelegate = startModStep(step);
            if (currentDelegate == null) {
                abort("Failed to start multi-block craft: " + step.recipeId());
                return true;
            }
            waitTicks = 0;
        }
        return false;
    }

    public boolean isDone() { return aborted || currentStepIdx >= steps.size(); }
    public boolean isAborted() { return aborted; }
    public ServerPlayer getPlayer() { return player; }
    public int stepsCount() { return steps.size(); }

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
                            abort("Missing: " + describeIngredientSafe(ing));
                            return false;
                        }
                    }
                }

                ItemStack result = cr.getResultItem(player.serverLevel().registryAccess());
                if (!result.isEmpty()) {
                    addToVirtualInventory(result);
                }
                for (ItemStack secondary : ModRecipeIndex.tryGetSecondaryOutputs(cr, player.serverLevel().registryAccess())) {
                    addToVirtualInventory(secondary);
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

                ItemStack result = ModRecipeIndex.tryGetResultItem(
                        recipe, player.serverLevel().registryAccess());
                if (!result.isEmpty()) {
                    addToVirtualInventory(result);
                }
                for (ItemStack secondary : ModRecipeIndex.tryGetSecondaryOutputs(recipe, player.serverLevel().registryAccess())) {
                    addToVirtualInventory(secondary);
                }
            }
        }
        return true;
    }

    // ── multi-block step execution ───────────────────────────────

    @Nullable
    private IBatchDelegate startModStep(CraftingResolver.ResolutionStep step) {
        BoundMachine machine = findBoundMachine(step.modType());
        if (machine == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] No bound machine for mod type {}",
                    step.modType());
            return null;
        }

        IBatchDelegate delegate = createDelegate(step.modType());
        if (delegate == null) return null;

        try {
            if (!delegate.validateAndInit(player, step.recipeId(), machine.dim(), machine.pos())) {
                RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Delegate validateAndInit failed for {}",
                        step.recipeId());
                return null;
            }

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
                    // No items placed in machine slots yet, but clean up delegate state
                    try { delegate.onBatchFailed(player, "pre-reserve failed"); } catch (Exception fe) {}
                    return null;
                }
                if (!delegate.tryStartWithMaterials(player, materials, ledger)) {
                    RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Delegate tryStartWithMaterials failed for {}",
                            step.recipeId());
                    // Clean up items already placed in machine slots BEFORE refunding
                    try { delegate.onBatchFailed(player, "tryStartWithMaterials failed"); } catch (Exception fe) {}
                    // Release ledger reservations so they don't inflate future availability checks
                    ledger.releaseReservations(materials);
                    // Refund pre-reserved materials back to chain resources
                    for (ItemStack m : materials) {
                        if (!m.isEmpty()) addToVirtualInventory(m);
                    }
                    return null;
                }
            } else {
                // Fallback: delegate manages its own extraction (backward compat)
                if (!delegate.tryStartSingleCraft(player, ledger)) {
                    RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Delegate tryStartSingleCraft failed for {}",
                            step.recipeId());
                    // Clean up items already placed in machine slots
                    try { delegate.onBatchFailed(player, "tryStartSingleCraft failed"); } catch (Exception fe) {}
                    return null;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] Error starting multi-block step", e);
            try { delegate.onBatchFailed(player, "exception in startModStep"); } catch (Exception fe) {}
            return null;
        }

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
                // Release any ledger reservations made during this partial attempt
                // so they don't leak and inflate future availability checks.
                ledger.releaseReservations(materials);
                // Refund materials sourced from virtual inventory back to it.
                for (ItemStack m : materials) {
                    if (!m.isEmpty()) addToVirtualInventory(m);
                }
                RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] preReserveStepMaterials failed: need {} more of ingredient for step {}",
                        needed, steps.get(currentStepIdx).recipeId());
                return null;
            }
            materials.add(material);
        }
        return materials;
    }

    @Nullable
    private BoundMachine findBoundMachine(ModType type) {
        List<BoundMachine> machines = AltarBindingRegistry.getBoundMachinesForType(player, type);
        return machines.isEmpty() ? null : machines.get(0);
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
                network.insertItem(vi.copy(), vi.getCount(),
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
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
            return;
        }

        // Flush remaining virtual inventory into RS network
        for (ItemStack vi : virtualInventory) {
            if (!vi.isEmpty()) {
                network.insertItem(vi.copy(), vi.getCount(),
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            }
        }

        RSIntegrationMod.LOGGER.debug("[RSI-AsyncChain] Chain complete for player {}: {} steps",
                player.getName().getString(), steps.size());
    }

    public void abort(String reason) {
        if (aborted) return;
        aborted = true;
        abortReason = reason;

        RSIntegrationMod.LOGGER.warn("[RSI-AsyncChain] Aborting chain for {}: {}",
                player.getName().getString(), reason);

        // Clean up active delegate (refunds items placed in machine)
        if (currentDelegate != null) {
            try {
                currentDelegate.onBatchFailed(player, reason);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-AsyncChain] Error in onBatchFailed", e);
            }
            currentDelegate = null;
        }

        // Discard virtual inventory — the ledger was never committed,
        // so inputs were never extracted. Flushing virtual items to RS
        // would create free items out of nothing.
        virtualInventory.clear();

        player.sendSystemMessage(Component.translatable("rsi.async.chain_aborted", reason));
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
