package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.ModVersionDelegateRegistry;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.loadbalancer.LoadBalancer;
import com.huanghuang.rsintegration.crafting.loadbalancer.ParallelCraftGroup;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.network.ProtectionChecker;
import com.huanghuang.rsintegration.util.CraftLogContext;
import com.huanghuang.rsintegration.util.Diagnostics;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
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
    private final MinecraftServer server;
    private final INetwork network;
    private final List<CraftingResolver.ResolutionStep> steps;
    private final List<ItemStack> virtualInventory = new ArrayList<>();
    private final ExtractionLedger ledger = new ExtractionLedger();
    private final CraftLogContext ctx;

    private int currentStepIdx;
    private IBatchDelegate currentDelegate;
    private int waitTicks;
    private int stepRemaining;
    private State state = State.PENDING;
    private String abortReason = "";
    private Runnable onDoneCallback;
    private int machineCount = 1;
    private int dropsThisChain;
    private boolean dropThrottleTripped;
    private static final int MAX_DROPS_PER_CHAIN = 20;

    public AsyncCraftChain(UUID playerId, MinecraftServer server, INetwork network,
                           List<CraftingResolver.ResolutionStep> steps) {
        this.playerId = playerId;
        this.server = server;
        this.network = network;
        this.steps = steps;
        ResourceLocation primaryRecipe = steps.isEmpty() ? new ResourceLocation("rsintegration", "empty_chain")
                : steps.get(0).recipeId();
        this.ctx = CraftLogContext.create(playerId, primaryRecipe);
        this.ledger.setLogContext(ctx);
        RSIntegrationMod.LOGGER.debug(ctx.format("Chain created: {} steps"), steps.size());
        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder(ctx.format("Steps: ["));
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
            RSIntegrationMod.LOGGER.debug(ctx.format("PENDING → EXECUTING"));
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
                    // Add secondary byproducts from multi-block recipes.
                    // GenericBatchDelegate captures secondaries internally and
                    // exposes them via getPendingSecondary() to avoid voiding
                    // remainders (empty buckets, etc.) that the recipe-level
                    // lookup below would miss for non-CraftingRecipe types.
                    if (currentDelegate instanceof GenericBatchDelegate gbd) {
                        for (ItemStack secondary : gbd.getPendingSecondary()) {
                            addToVirtualInventory(secondary);
                        }
                    } else {
                        ServerLevel overworld = server.overworld();
                        if (overworld == null) return true;
                        Recipe<?> mbRecipe = overworld.getRecipeManager()
                                .byKey(steps.get(currentStepIdx).recipeId()).orElse(null);
                        if (mbRecipe != null) {
                            for (ItemStack secondary : ModRecipeHandlers.tryGetSecondaryOutputs(mbRecipe, overworld.registryAccess())) {
                                addToVirtualInventory(secondary);
                            }
                        }
                    }
                    // Update machineCount to reflect actual successes, not
                    // the pre-allocation count (failed machines were removed).
                    if (currentDelegate instanceof ParallelCraftGroup group) {
                        int actual = group.getChildCount();
                        if (actual != this.machineCount) {
                            RSIntegrationMod.LOGGER.debug(ctx.format("machineCount corrected: {} → {}"),
                                    this.machineCount, actual);
                            this.machineCount = actual;
                        }
                    }
                    try {
                        currentDelegate.onBatchFinished(online);
                    } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFinished error"), fe);
                    }
                    currentDelegate = null;
                    waitTicks = 0;
                    // Ledger was committed before placement in startModStep.
                    // Reset for the next step.
                    ledger.reset();
                    stepRemaining -= machineCount;
                    if (stepRemaining > 0) {
                        state = State.EXECUTING;
                    } else {
                        currentStepIdx++;
                        state = State.EXECUTING;
                    }
                    RSIntegrationMod.LOGGER.debug(ctx.format("WAITING_MOD → EXECUTING (remaining={})"), stepRemaining);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error polling craft completion"), e);
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
            if (stepRemaining <= 0) {
                stepRemaining = step.executions();
                machineCount = 1;
            }
            currentDelegate = startModStep(step, online);
            if (currentDelegate == null) {
                abort("Failed to start multi-block craft: " + step.recipeId());
                return true;
            }
            state = State.WAITING_MOD;
            Diagnostics.record(Diagnostics.Category.CHAIN_STATE,
                    "EXECUTING→WAITING_MOD step=" + step.recipeId(),
                    step.recipeId(), step.modType());
            RSIntegrationMod.LOGGER.debug(ctx.format("EXECUTING → WAITING_MOD for step {}"),
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
        List<CraftingResolver.ResolutionStep> vanillaSteps = new ArrayList<>();
        int i = startIdx;
        while (i < steps.size() && steps.get(i).modType() == ModType.GENERIC) {
            vanillaSteps.add(steps.get(i));
            i++;
        }

        if (!vanillaSteps.isEmpty()) {
            executeVanillaStepsInline(vanillaSteps, online);
        }
        return i;
    }

    /**
     * Execute vanilla crafting steps inline, using the chain's virtual inventory
     * and ledger so intermediate outputs feed forward across the entire chain.
     */
    private boolean executeVanillaStepsInline(List<CraftingResolver.ResolutionStep> vanillaSteps, ServerPlayer online) {
        ServerLevel overworld = server.overworld();
        if (overworld == null) return false;
        RecipeManager rm = overworld.getRecipeManager();
        RSIntegrationMod.LOGGER.debug(ctx.format("executeVanillaStepsInline: {} vanilla steps, currentStepIdx={}"),
                vanillaSteps.size(), currentStepIdx);
        logVirtualInventory("before batch");

        for (CraftingResolver.ResolutionStep step : vanillaSteps) {
            ResourceLocation stepId = step.recipeId();
            int executions = step.executions();
            Recipe<?> recipe = rm.byKey(stepId).orElse(null);
            if (recipe == null) {
                RSIntegrationMod.LOGGER.debug(ctx.format("  step {} not found in recipe manager"), stepId);
                continue;
            }

            RSIntegrationMod.LOGGER.debug(ctx.format("  processing step: {} x{}"), stepId, executions);

            if (recipe instanceof net.minecraft.world.item.crafting.CraftingRecipe cr) {
                // Track only the slots actually modified so we can roll back
                // just those slots on failure — avoids full-inventory snapshot copies.
                Map<Integer, ItemStack> modifiedSlots = new HashMap<>();

                // Capture the actual items consumed (with NBT) so assemble()
                // can transfer input data to the output.  getResultItem()
                // returns a bare template that discards backpack contents,
                // blade stats, enchantments, etc.
                List<Ingredient> ingredients = cr.getIngredients();
                ItemStack[] consumed = new ItemStack[Math.min(ingredients.size(), 9)];

                for (int ingIdx = 0; ingIdx < ingredients.size(); ingIdx++) {
                    Ingredient ing = ingredients.get(ingIdx);
                    if (ing.isEmpty()) continue;
                    int stillNeeded = executions;
                    boolean captured = false;
                    for (int i = 0; i < virtualInventory.size() && stillNeeded > 0; i++) {
                        ItemStack vi = virtualInventory.get(i);
                        if (vi.isEmpty()) continue;
                        if (ing.test(vi)) {
                            modifiedSlots.putIfAbsent(i, vi.copy());
                            if (!captured && ingIdx < 9) {
                                consumed[ingIdx] = vi.copyWithCount(1);
                                captured = true;
                            }
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
                        if (!captured && ingIdx < 9) {
                            consumed[ingIdx] = reserved.copyWithCount(1);
                        }
                    }
                }

                ItemStack result = CraftPacketUtils.assembleCraftingOutput(cr, consumed, online);
                if (!result.isEmpty()) {
                    addToVirtualInventory(result.copyWithCount(StepExecutor.mulCount(result.getCount(), executions)));
                }
                for (ItemStack secondary : ModRecipeHandlers.tryGetSecondaryOutputs(cr, server.overworld().registryAccess())) {
                    addToVirtualInventory(secondary.copyWithCount(StepExecutor.mulCount(secondary.getCount(), executions)));
                }
                for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(cr)) {
                    addToVirtualInventory(remainder.copyWithCount(StepExecutor.mulCount(remainder.getCount(), executions)));
                }
            } else {
                // Non-crafting GENERIC recipe (e.g. sawmill, custom mod type)
                List<IngredientSpec> specs =
                        CraftPacketUtils.extractIngredientSpecs(recipe);
                if (specs == null || specs.isEmpty()) continue;

                for (IngredientSpec spec : specs) {
                    if (spec.isEmpty()) continue;
                    int stillNeeded = StepExecutor.mulCount(spec.count(), executions);
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

                ItemStack result = ModRecipeHandlers.tryGetResultItem(
                        recipe, server.overworld().registryAccess());
                if (!result.isEmpty()) {
                    addToVirtualInventory(result.copyWithCount(StepExecutor.mulCount(result.getCount(), executions)));
                }
                for (ItemStack secondary : ModRecipeHandlers.tryGetSecondaryOutputs(recipe, server.overworld().registryAccess())) {
                    addToVirtualInventory(secondary.copyWithCount(StepExecutor.mulCount(secondary.getCount(), executions)));
                }
                for (IngredientSpec spec : specs) {
                    if (spec.isEmpty()) continue;
                    for (ItemStack stack : spec.ingredient().getItems()) {
                        if (stack.isEmpty()) continue;
                        try {
                            ItemStack remainder = stack.getCraftingRemainingItem();
                            if (!remainder.isEmpty()) {
                                addToVirtualInventory(remainder.copyWithCount(StepExecutor.mulCount(spec.count(), executions)));
                                break;
                            }
                        } catch (Exception e) {
                            RSIntegrationMod.LOGGER.debug(ctx.format("getCraftingRemainingItem failed"), e);
                        }
                    }
                }
            }
        }
        return true;
    }

    // ── multi-block step execution ───────────────────────────────

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
            RSIntegrationMod.LOGGER.warn(ctx.format("No bound machine for mod type {} subType={} (total {} bindings for this mod)"),
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

        // ── Same-dimension priority ──
        // Prefer machines in the player's current dimension so cross-dimension
        // crafts only fall back to remote dimensions when no local machine exists.
        ResourceLocation playerDim = online.level().dimension().location();
        machines.sort((a, b) -> {
            boolean aSame = a.dim().equals(playerDim);
            boolean bSame = b.dim().equals(playerDim);
            if (aSame == bSame) return 0;
            return aSame ? -1 : 1;
        });

        // ── Load-balanced multi-machine dispatch ──
        // When multiple machines are bound for this mod type, try to distribute
        // work across them instead of sending everything to one machine.
        // Only parallelize when there are multiple executions to distribute;
        // a single-execution step would double the material requirement (one
        // set per child) and fail if the player only has enough for one craft.
        if (step.inferMode()) {
            RSIntegrationMod.LOGGER.debug(ctx.format("[LB] skipped: inferMode=true"));
        } else if (machines.size() < 2) {
            RSIntegrationMod.LOGGER.debug(ctx.format("[LB] skipped: only {} bound machine(s)"), machines.size());
        } else if (step.executions() <= 1) {
            RSIntegrationMod.LOGGER.debug(ctx.format("[LB] skipped: executions={}"), step.executions());
        } else {
            RSIntegrationMod.LOGGER.debug(ctx.format("[LB] attempting parallel: {} machines, {} executions"),
                    machines.size(), step.executions());
            IBatchDelegate parallel = tryStartParallel(machines, step, online);
            if (parallel != null) {
                RSIntegrationMod.LOGGER.debug(ctx.format("[LB] parallel dispatch OK: childCount={}"),
                        parallel instanceof ParallelCraftGroup g ? g.getChildCount() : 1);
                return parallel;
            }
            RSIntegrationMod.LOGGER.debug(ctx.format("[LB] parallel dispatch failed, falling back to single-machine"));
            // Fall through: single-machine path below
        }

        IBatchDelegate delegate = step.inferMode()
                ? step.modType().createInferDelegate()
                : createDelegate(step.modType());
        if (delegate == null) return null;

        // GenericBatchDelegate computes the result from pre-reserved materials
        // without a physical machine.  Use the shared-ledger preReserve flow so
        // intermediate outputs from prior chain steps (in virtualInventory) are
        // visible to subsequent steps.
        if (delegate instanceof GenericBatchDelegate) {
            if (!delegate.validateAndInit(online, step.recipeId(), null, null)) {
                return null;
            }
            if (delegate instanceof AbstractBatchDelegate abd) {
                abd.setMachineServer(server);
            }
            return startGenericStep(delegate, step, online);
        }

        // Try each bound machine until one validates successfully.
        BoundMachine matchedMachine = null;
        for (BoundMachine m : machines) {
            try {
                if (delegate.validateAndInit(online, step.recipeId(), m.dim(), m.pos())) {
                    matchedMachine = m;
                    if (delegate instanceof AbstractBatchDelegate abd) {
                        abd.setMachineDim(m.dim());
                        abd.setMachineServer(server);
                    }
                    break;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug(ctx.format("validateAndInit failed for machine at {}"), m.pos(), e);
            }
        }
        if (matchedMachine == null) {
            // Machines were found but validateAndInit failed on every one.
            // The delegate already printed a specific error (pedestal, energy, etc.).
            RSIntegrationMod.LOGGER.warn(ctx.format("All {} bound machines failed validateAndInit for mod type {}: recipe={}"),
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
            RSIntegrationMod.LOGGER.warn(ctx.format("Protection check failed"), e);
        }

        try {
            List<IngredientSpec> specs = delegate.getRequiredMaterials();
            if (specs != null && !specs.isEmpty()) {
                List<ItemStack> materials = preReserveStepMaterials(specs, online);
                if (materials == null) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("Failed to pre-reserve materials for {}"),
                            step.recipeId());
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials", step.recipeId()));
                    try { delegate.onBatchFailed(online, "pre-reserve failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during pre-reserve cleanup"), fe);
}
                    return null;
                }
                if (!ledger.commit(network, online)) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("Ledger commit failed for {}"),
                            step.recipeId());
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials", step.recipeId()));
                    try { delegate.onBatchFailed(online, "commit failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during commit cleanup"), fe);
}
                    return null;
                }
                if (!delegate.tryStartWithMaterials(online, materials, ledger)) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("Delegate tryStartWithMaterials failed for {}"),
                            step.recipeId());
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.craft_failed", step.recipeId()));
                    try { delegate.onBatchFailed(online, "tryStartWithMaterials failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during tryStartWithMaterials cleanup"), fe);
}
                    // abort() refunds the ledger, so we must NOT refund here
                    return null;
                }
            } else {
                if (!delegate.tryStartSingleCraft(online, ledger)) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("Delegate tryStartSingleCraft failed for {}"),
                            step.recipeId());
                    try { delegate.onBatchFailed(online, "tryStartSingleCraft failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during tryStartSingleCraft cleanup"), fe);
}
                    return null;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error(ctx.format("Error starting multi-block step"), e);
            try { delegate.onBatchFailed(online, "exception in startModStep"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during exception cleanup"), fe);
}
            return null;
        }

        RSIntegrationMod.LOGGER.debug(ctx.format("Multi-block step started OK: recipe={} delegate={}"),
                step.recipeId(), delegate.getClass().getSimpleName());
        return delegate;
    }

    // ── generic (no-machine) delegate: shared-ledger pre-reserve flow ──

    private IBatchDelegate startGenericStep(IBatchDelegate delegate,
                                            CraftingResolver.ResolutionStep step,
                                            ServerPlayer online) {
        try {
            List<IngredientSpec> specs = delegate.getRequiredMaterials();
            if (specs != null && !specs.isEmpty()) {
                List<ItemStack> materials = preReserveStepMaterials(specs, online);
                if (materials == null) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("Failed to pre-reserve for generic step {}"),
                            step.recipeId());
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials", step.recipeId()));
                    try { delegate.onBatchFailed(online, "pre-reserve failed"); } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during pre-reserve cleanup"), fe);
                    }
                    return null;
                }
                if (!ledger.commit(network, online)) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("Ledger commit failed for generic step {}"),
                            step.recipeId());
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials", step.recipeId()));
                    try { delegate.onBatchFailed(online, "commit failed"); } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during commit cleanup"), fe);
                    }
                    return null;
                }
                if (!delegate.tryStartWithMaterials(online, materials, ledger)) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("tryStartWithMaterials failed for generic step {}"),
                            step.recipeId());
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.craft_failed", step.recipeId()));
                    try { delegate.onBatchFailed(online, "tryStartWithMaterials failed"); } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during tryStartWithMaterials cleanup"), fe);
                    }
                    // abort() refunds the ledger, so we must NOT refund here
                    return null;
                }
            } else {
                // Fallback: delegate handles extraction on its own (legacy path).
                // Note: this does NOT see virtualInventory; new delegates should
                // implement getRequiredMaterials() + tryStartWithMaterials().
                if (!delegate.tryStartSingleCraft(online, ledger)) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("tryStartSingleCraft failed for generic step {}"),
                            step.recipeId());
                    try { delegate.onBatchFailed(online, "tryStartSingleCraft failed"); } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during cleanup"), fe);
                    }
                    return null;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error(ctx.format("Error in generic step"), e);
            try { delegate.onBatchFailed(online, "exception in startGenericStep"); } catch (Exception fe) {
                RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during exception cleanup"), fe);
            }
            return null;
        }

        RSIntegrationMod.LOGGER.debug(ctx.format("Generic step started OK: recipe={}"),
                step.recipeId());
        return delegate;
    }

    // ── parallel (load-balanced) step ──────────────────────────────

    /**
     * Try to dispatch work across multiple bound machines of the same type.
     * Returns a {@link ParallelCraftGroup} if at least 2 machines are available;
     * returns null to fall through to the single-machine path.
     */
    private IBatchDelegate tryStartParallel(List<BoundMachine> machines,
                                            CraftingResolver.ResolutionStep step,
                                            ServerPlayer online) {
        if (server == null) return null;

        // Filter: chunk loaded, BE present, not busy (resolves per-machine dimension)
        List<BoundMachine> available = LoadBalancer.filterAvailable(machines, server);
        if (available.size() < 2) {
            RSIntegrationMod.LOGGER.debug(ctx.format("[LB] filterAvailable: {} machines → {} available (<2, abort)"),
                    machines.size(), available.size());
            return null;
        }
        RSIntegrationMod.LOGGER.debug(ctx.format("[LB] filterAvailable: {} machines → {} available"),
                machines.size(), available.size());

        // Cap children at remaining executions — don't start more
        // machines than the number of crafts still needed.
        int cap = Math.min(step.executions(), stepRemaining);
        if (available.size() > cap) {
            available = new ArrayList<>(available.subList(0, cap));
        }

        // Build a parallel group — constructor internally creates and validates
        // one delegate per machine
        ParallelCraftGroup group = new ParallelCraftGroup(available, step.modType(),
                step.recipeId(), online);
        if (!group.validateAndInit(online, step.recipeId(), null, BlockPos.ZERO)) {
            RSIntegrationMod.LOGGER.debug(ctx.format("Parallel group empty — all children failed validateAndInit"));
            return null;
        }

        this.machineCount = group.getChildCount();
        group.setMachineServer(server);
        RSIntegrationMod.LOGGER.info(ctx.format("Load-balanced: {} machines for recipe {}"),
                group.getChildCount(), step.recipeId());

        // Parallel groups use the tryStartSingleCraft path (each child extracts
        // its own materials independently)
        return startParallelStep(group, step, online);
    }

    private IBatchDelegate startParallelStep(IBatchDelegate group,
                                             CraftingResolver.ResolutionStep step,
                                             ServerPlayer online) {
        try {
            // Mirror the single-machine path: pre-reserve from virtualInventory
            // first so intermediate outputs from prior steps are visible to all children.
            List<IngredientSpec> specs = group.getRequiredMaterials();
            if (specs != null && !specs.isEmpty()) {
                List<ItemStack> materials = preReserveStepMaterials(specs, online);
                if (materials == null) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("Failed to pre-reserve for parallel step {}"),
                            step.recipeId());
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials", step.recipeId()));
                    try { group.onBatchFailed(online, "pre-reserve failed"); } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during pre-reserve cleanup"), fe);
                    }
                    return null;
                }
                if (!ledger.commit(network, online)) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("Ledger commit failed for parallel {}"),
                            step.recipeId());
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials", step.recipeId()));
                    try { group.onBatchFailed(online, "commit failed"); } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during commit cleanup"), fe);
                    }
                    return null;
                }
                if (!group.tryStartWithMaterials(online, materials, ledger)) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("Parallel group tryStartWithMaterials failed for {}"),
                            step.recipeId());
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.craft_failed", step.recipeId()));
                    try { group.onBatchFailed(online, "tryStartWithMaterials failed"); } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during parallel cleanup"), fe);
                    }
                    // abort() refunds the ledger, so we must NOT refund here
                    return null;
                }
            } else {
                // Fallback: each child self-extracts (no virtualInventory visibility)
                if (!group.tryStartSingleCraft(online, ledger)) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("Parallel group tryStartSingleCraft failed for {}"),
                            step.recipeId());
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.craft_failed", step.recipeId()));
                    try { group.onBatchFailed(online, "tryStartSingleCraft failed"); } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during parallel cleanup"), fe);
                    }
                    return null;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error(ctx.format("Error starting parallel step"), e);
            try { group.onBatchFailed(online, "exception in startParallelStep"); } catch (Exception fe) {
                RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during exception cleanup"), fe);
            }
            return null;
        }

        RSIntegrationMod.LOGGER.debug(ctx.format("Parallel step started OK: recipe={}"), step.recipeId());
        return group;
    }

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
                    needed -= reserved.getCount();
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
                    needed -= reserved.getCount();
                }
            }

            if (needed > 0) {
                if (!material.isEmpty()) {
                    ledger.releaseReservations(List.of(material));
                }
                ledger.releaseReservations(materials);
                virtualInventory.clear();
                virtualInventory.addAll(virtualSnapshot);
                RSIntegrationMod.LOGGER.warn(ctx.format("preReserveStepMaterials failed: need {} more of '{}' (spec {}/{}) for step {}"),
                        needed, CraftPacketUtils.describeIngredient(spec.ingredient()).getString(),
                        materials.size() + 1, specs.size(), steps.get(currentStepIdx).recipeId());
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

    private void flushVirtualInventory(ServerPlayer online) {
        if (network == null) return;
        var iter = virtualInventory.iterator();
        while (iter.hasNext()) {
            ItemStack vi = iter.next();
            if (!vi.isEmpty()) {
                ItemStack leftover = network.insertItem(vi.copy(), vi.getCount(),
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                if (online != null) {
                    var tracker = network.getItemStorageTracker();
                    if (tracker != null) tracker.changed(online, vi.copy());
                }
                if (online != null && !leftover.isEmpty()) {
                    safeGiveToPlayer(online, leftover);
                } else if (online == null && !leftover.isEmpty()) {
                    if (!insertOrDropAtSpawn(leftover)) {
                        // Throttle tripped — stop flushing and abort so
                        // remaining items are not silently destroyed.
                        // Leave unconsumed items in virtualInventory for
                        // the abort path to refund.
                        abort("Drop throttle tripped — RS network full and player offline");
                        return;
                    }
                }
                iter.remove();
            }
        }
    }

    /**
     * Try to insert into RS, then drop at world spawn as last resort.
     * @return true if the item was handled, false if the chain should abort
     *         (throttle tripped — further drops would be silently discarded)
     */
    private boolean insertOrDropAtSpawn(ItemStack stack) {
        if (network != null) {
            ItemStack stillLeft = network.insertItem(stack.copy(), stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (stillLeft.isEmpty()) return true;
            stack = stillLeft;
        }
        if (dropThrottleTripped) {
            RSIntegrationMod.LOGGER.warn("[RSI] Drop throttle tripped — discarding {} x{} for player {}",
                    stack.getHoverName().getString(), stack.getCount(), playerId);
            return false;
        }
        dropsThisChain++;
        if (dropsThisChain > MAX_DROPS_PER_CHAIN) {
            dropThrottleTripped = true;
            RSIntegrationMod.LOGGER.warn("[RSI] Drop throttle tripped ({} drops) — discarding {} x{} and all future drops for player {}. Chain will abort.",
                    MAX_DROPS_PER_CHAIN, stack.getHoverName().getString(), stack.getCount(), playerId);
            return false;
        }
        if (server != null) {
            var spawnLevel = server.overworld();
            if (spawnLevel == null) return true;
            var spawnPos = spawnLevel.getSharedSpawnPos();
            spawnLevel.addFreshEntity(
                new ItemEntity(spawnLevel,
                    spawnPos.getX() + 0.5, spawnPos.getY() + 0.5, spawnPos.getZ() + 0.5, stack.copy()));
            RSIntegrationMod.LOGGER.warn("[RSI] Item dropped at world spawn (player {} offline): {} x{}",
                playerId, stack.getHoverName().getString(), stack.getCount());
        }
        return true;
    }

    // ── lifecycle ────────────────────────────────────────────────

    private void finish(ServerPlayer online) {
        if (!ledger.commit(network, online)) {
            RSIntegrationMod.LOGGER.warn(ctx.format("Commit failed for player {} after {} steps"),
                    online.getName().getString(), steps.size());
            online.sendSystemMessage(Component.translatable("rsi.async.error.commit_failed"));
            abort("Final commit failed");
            return;
        }

        if (network != null) {
            for (ItemStack vi : virtualInventory) {
                if (!vi.isEmpty()) {
                    ItemStack leftover = network.insertItem(vi.copy(), vi.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    var tracker = network.getItemStorageTracker();
                    if (tracker != null) tracker.changed(online, vi.copy());
                    if (!leftover.isEmpty()) {
                        safeGiveToPlayer(online, leftover);
                    }
                }
            }
        }

        state = State.COMPLETED;
        Diagnostics.record(Diagnostics.Category.CHAIN_STATE, "→COMPLETED steps=" + steps.size());
        RSIntegrationMod.LOGGER.info(ctx.format("COMPLETED for player {}: {} steps"),
                online.getName().getString(), steps.size());
        fireOnDone();
    }

    private void fireOnDone() {
        if (onDoneCallback != null) {
            try { onDoneCallback.run(); } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("onDone callback threw"), e);
            }
        }
    }

    public void onDone(@Nullable Runnable callback) {
        this.onDoneCallback = callback;
    }

    /** How many machines produced output this chain run (1 for single machine, N for parallel). */
    public int getMachineCount() {
        return machineCount;
    }

    public void abort(String reason) {
        if (state == State.ABORTED || state == State.COMPLETED) return;

        ServerPlayer online = resolvePlayer();
        RSIntegrationMod.LOGGER.warn(ctx.format("Aborting chain (state={}) for {}: {}"),
                state, online != null ? online.getName().getString() : playerId, reason);
        Diagnostics.record(Diagnostics.Category.CHAIN_STATE,
                "→ABORTED reason=" + reason + " atStep=" + currentStepIdx + "/" + steps.size());
        state = State.ABORTED;
        abortReason = reason;

        // Delegate cleanup — works even when player is offline
        if (currentDelegate != null) {
            try {
                currentDelegate.onBatchFailed(online, reason);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error in onBatchFailed"), e);
            }
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

        // Do NOT flush virtualInventory on abort — the ledger refunded/rolled-back
        // the inputs, so flushing intermediate products would duplicate items.
        virtualInventory.clear();

        // Only send message if player is still online
        if (online != null) {
            online.sendSystemMessage(Component.translatable("rsi.async.chain_aborted", reason));
        }
        ledger.close();
        fireOnDone();
    }

    private void abortSilently(String reason) {
        if (state == State.ABORTED || state == State.COMPLETED) return;

        RSIntegrationMod.LOGGER.warn(ctx.format("Aborting silently (state={}) for {}: {}"),
                state, playerId, reason);
        Diagnostics.record(Diagnostics.Category.CHAIN_STATE,
                "→ABORTED_SILENT reason=" + reason + " atStep=" + currentStepIdx + "/" + steps.size());
        state = State.ABORTED;
        abortReason = reason;

        if (currentDelegate != null) {
            try {
                currentDelegate.onBatchFailed(null, reason);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error in silent delegate cleanup"), e);
            }
            currentDelegate = null;
        }

        // Do NOT flush virtualInventory on abort — see abort() for rationale.
        virtualInventory.clear();

        if (network != null) {
            try {
                ledger.refundCommitted(network, null);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Failed to refund ledger on silent abort"), e);
            }
        }

        ledger.close();
        fireOnDone();
    }

    // ── delegate factory ─────────────────────────────────────────

    private static IBatchDelegate createDelegate(ModType type) {
        if (type == ModType.GENERIC) return null;
        // 1. Check version-specific delegate registry first
        Class<? extends IBatchDelegate> versioned = ModVersionDelegateRegistry.resolve(type);
        if (versioned != null) {
            try {
                return versioned.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI] Failed to instantiate versioned delegate {}",
                        versioned.getName(), e);
            }
        }
        // 2. Fall back to default delegate
        return type.createDelegate();
    }

    // ── safe item give ───────────────────────────────────────────

    private void safeGiveToPlayer(ServerPlayer player, ItemStack stack) {
        PlayerUtils.safeGiveToPlayer(player, stack, network);
    }

    // ── debug helpers ────────────────────────────────────────────

    private static String describeIngredientSafe(Ingredient ing) {
        for (ItemStack stack : ing.getItems()) {
            if (!stack.isEmpty()) return stack.getHoverName().getString();
        }
        return "Unknown";
    }

    private void logMissingIngredient(Ingredient ing, ResourceLocation stepId) {
        StringBuilder sb = new StringBuilder(ctx.format("Missing ingredient for step "));
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
        StringBuilder sb = new StringBuilder(ctx.format("VirtualInventory "));
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
        RSIntegrationMod.LOGGER.debug(ctx.format("Ledger entries={} committed={}"),
                ledger.size(), ledger.isCommitted());
        RSIntegrationMod.LOGGER.debug(ctx.format("Network available (sample): {}"),
                ledger.describePending());
    }
}
