package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
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
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        WAITING_PLAYER_TRANSFORMATION, // Waiting for inventory tick to taint Earth Heart
        COMPLETING,     // Final commit + flush in progress
        COMPLETED,      // Successfully finished
        ABORTED         // Failed
    }

    private final UUID craftId;
    private final UUID playerId;
    private final MinecraftServer server;
    private final INetwork network;
    private final List<CraftingResolver.ResolutionStep> steps;
    private final List<ItemStack> virtualInventory = new ArrayList<>();
    /**
     * Snapshot of {@link #virtualInventory} at the last <i>settled</i> commit
     * boundary — i.e. the set of intermediate products whose backing inputs were
     * already irreversibly consumed (a committed vanilla batch, a started mod
     * step, or a completed mod step). On abort this is the exact set owed to the
     * player: it excludes in-flight products from reservations that get rolled
     * back, and excludes materials already locked into a physical machine, so
     * flushing it never overlaps the ledger refund.
     */
    private final List<ItemStack> committedVirtual = new ArrayList<>();
    private final ExtractionLedger ledger = new ExtractionLedger();
    private final CraftLogContext ctx;

    private int currentStepIdx;
    private IBatchDelegate currentDelegate;
    private int waitTicks;
    private int drainingTicks;
    private int stepRemaining;
    private State state = State.PENDING;
    private int taintSlot = -1;
    private int taintRemaining;
    private int taintWaitTicks;
    private String abortReason = "";
    private final TerminalListeners terminalListeners;
    private int machineCount = 1;
    private int dropsThisChain;
    private boolean dropThrottleTripped;
    private static final int MAX_DROPS_PER_CHAIN = 20;

    /**
     * The concrete output the player asked for, captured from the JEI ghost
     * output slot. Applied to the delegate that produces the primary recipe
     * (step 0) so it can distinguish NBT-variant outputs sharing one recipe id
     * (e.g. WR arcane iterator "Curse II" vs "Curse I"). Null when unsupplied.
     */
    @Nullable
    private ItemStack targetOutput;

    /** Active one-shot output capture for the current physical-machine step. */
    @Nullable
    private CraftOutputInterceptor.CaptureHandle captureHandle;

    @Nullable
    private final CraftPlanGraph graph;
    @Nullable
    private final DagScheduler graphScheduler;
    private final boolean useGraphExecution;
    @Nullable
    private NodeId currentGraphNode;

    public AsyncCraftChain(UUID playerId, MinecraftServer server, INetwork network,
                           List<CraftingResolver.ResolutionStep> steps) {
        this(UUID.randomUUID(), playerId, server, network, steps, null);
    }

    public AsyncCraftChain(UUID playerId, MinecraftServer server, INetwork network,
                           CraftPlanGraph graph) {
        this(UUID.randomUUID(), playerId, server, network, projectSteps(graph), graph);
    }

    public AsyncCraftChain(UUID playerId, MinecraftServer server, INetwork network,
                           CraftPlanGraph graph, CraftingResolver.ResolutionStep terminalStep,
                           int repeatCount) {
        this(UUID.randomUUID(), playerId, server, network,
                amplifyModSteps(appendTerminalStep(projectSteps(graph), terminalStep), repeatCount), graph);
    }

    AsyncCraftChain(UUID craftId, UUID playerId, MinecraftServer server, INetwork network,
                    List<CraftingResolver.ResolutionStep> steps) {
        this(craftId, playerId, server, network, steps, null);
    }

    private AsyncCraftChain(UUID craftId, UUID playerId, MinecraftServer server, INetwork network,
                            List<CraftingResolver.ResolutionStep> steps,
                            @Nullable CraftPlanGraph graph) {
        this.craftId = Objects.requireNonNull(craftId, "craftId");
        this.playerId = playerId;
        this.server = server;
        this.network = network;
        this.steps = List.copyOf(steps);
        if (graph != null) {
            CraftPlanValidator.validate(graph);
        }
        ResourceLocation primaryRecipe = steps.isEmpty() ? new ResourceLocation("rsintegration", "empty_chain")
                : steps.get(0).recipeId();
        this.ctx = CraftLogContext.create(playerId, primaryRecipe);
        this.terminalListeners = new TerminalListeners(
                error -> RSIntegrationMod.LOGGER.error(ctx.format("onDone callback threw"), error));
        this.ledger.setLogContext(ctx);
        if (graph != null) {
            this.graph = graph;
            this.graphScheduler = new DagScheduler(graph);
            this.useGraphExecution = true;
        } else {
            this.graph = null;
            this.graphScheduler = null;
            this.useGraphExecution = false;
        }
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

    private static List<CraftingResolver.ResolutionStep> projectSteps(CraftPlanGraph graph) {
        Objects.requireNonNull(graph, "graph");
        Map<com.huanghuang.rsintegration.crafting.graph.NodeId, CraftNode> nodes = graph.nodesById();
        List<CraftingResolver.ResolutionStep> projected = new ArrayList<>(graph.topologicalOrder().size());
        for (com.huanghuang.rsintegration.crafting.graph.NodeId nodeId : graph.topologicalOrder()) {
            CraftNode node = nodes.get(nodeId);
            if (node == null) throw new IllegalArgumentException("missing graph node " + nodeId);
            ModType modType = ModType.byId(node.modTypeId());
            if (modType == null) throw new IllegalArgumentException("unknown graph mod type " + node.modTypeId());
            projected.add(new CraftingResolver.ResolutionStep(node.recipeId(), modType,
                    node.recipeTypeId(), node.alternativeIds(), node.alternativeModTypeIds(),
                    node.inferMode(), node.executions(), node.syntheticInput(), node.syntheticOutput()));
        }
        return List.copyOf(projected);
    }

    private static List<CraftingResolver.ResolutionStep> appendTerminalStep(
            List<CraftingResolver.ResolutionStep> projected,
            CraftingResolver.ResolutionStep terminalStep) {
        List<CraftingResolver.ResolutionStep> result = new ArrayList<>(projected.size() + 1);
        result.addAll(projected);
        result.add(Objects.requireNonNull(terminalStep, "terminalStep"));
        return List.copyOf(result);
    }

    private static List<CraftingResolver.ResolutionStep> amplifyModSteps(
            List<CraftingResolver.ResolutionStep> source, int repeatCount) {
        if (repeatCount <= 1) return source;
        List<CraftingResolver.ResolutionStep> result = new ArrayList<>(source.size());
        for (CraftingResolver.ResolutionStep step : source) {
            if (step.modType() == ModType.GENERIC) {
                result.add(step);
            } else {
                result.add(new CraftingResolver.ResolutionStep(step.recipeId(), step.modType(),
                        step.recipeTypeId(), step.alternativeIds(), step.alternativeModTypes(),
                        step.inferMode(), StepExecutor.mulCount(step.executions(), repeatCount),
                        step.syntheticInput(), step.syntheticOutput()));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Set the concrete output the player asked for (from the JEI ghost slot).
     * Threaded to the delegate producing the primary recipe so it can pick the
     * correct NBT variant among outputs that share one recipe id. Must be called
     * before the chain starts ticking.
     */
    public void setTargetOutput(@Nullable ItemStack target) {
        this.targetOutput = target != null && !target.isEmpty() ? target.copy() : null;
    }

    /**
     * True for the step that produces the chain's primary (final) output — i.e.
     * the last step, whose recipe matches the recipe id the player clicked.
     * {@link #targetOutput} only applies to this step; intermediate steps craft
     * their own generic outputs and must not inherit the final target.
     */
    private boolean isPrimaryStep(int stepIdx) {
        return stepIdx == steps.size() - 1;
    }

    /**
     * Apply {@link #targetOutput} to a delegate, but only for the
     * {@linkplain #isPrimaryStep primary step}.
     */
    private void applyTargetOutput(AbstractBatchDelegate abd) {
        if (targetOutput != null && isPrimaryStep(currentStepIdx)) {
            abd.setTargetOutput(targetOutput);
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

        if (state == State.WAITING_PLAYER_TRANSFORMATION) {
            ServerPlayer online = resolvePlayer();
            if (online == null) {
                abortWithoutRefund("Player disconnected while Earth Heart was in inventory", Component.translatable("rsi.async.earth_heart.error.player_disconnected"));
                return true;
            }
            return tickEarthHeartTaint(online);
        }

        // Dynamic player lookup — null means player disconnected
        ServerPlayer online = resolvePlayer();

        // Use graph scheduler when available — serial mode by default
        if (useGraphExecution) {
            if (online == null) {
                abortSilently("Player disconnected");
                return true;
            }
            if (network != null && !network.canRun()) {
                abortSilently("RS controller removed or network invalidated");
                return true;
            }
            return tickGraph(online);
        }

        // First tick transition
        if (state == State.PENDING) {
            RSIntegrationMod.LOGGER.debug(ctx.format("PENDING → EXECUTING"));
            state = State.EXECUTING;
            Diagnostics.record(Diagnostics.Category.CHAIN_STATE, "PENDING→EXECUTING steps=" + steps.size());
        }
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
            try {
                // Capture and observation are processed before timeout so an output
                // produced on the deadline tick is never collected and then refunded.
                boolean capturedOutput = captureHandle != null && captureHandle.hasCaptured();
                IBatchDelegate.CraftObservation observation = currentDelegate.observeCraft(online.serverLevel());
                if (currentDelegate instanceof ParallelCraftGroup group && group.isDraining()) {
                    drainingTicks++;
                } else {
                    drainingTicks = 0;
                }
                if (currentDelegate instanceof ParallelCraftGroup group) {
                    List<ItemStack> settled = group.drainSettledResults();
                    if (!settled.isEmpty()) {
                        for (ItemStack result : settled) addToVirtualInventory(result);
                        snapshotCommittedVirtual();
                        waitTicks = 0;
                    }
                    List<ItemStack> queuedMaterials = group.drainQueuedMaterialsForRecovery();
                    if (!queuedMaterials.isEmpty()) {
                        for (ItemStack material : queuedMaterials) addToVirtualInventory(material);
                        snapshotCommittedVirtual();
                    }
                }
                if (observation.phase() == IBatchDelegate.CraftPhase.FAILED) {
                    abort("Machine craft failed: " + observation.detail());
                    return true;
                }
                if (observation.phase() == IBatchDelegate.CraftPhase.DONE) {
                    List<ItemStack> actualResults = new ArrayList<>(disarmOutputCapture());
                    actualResults.addAll(currentDelegate.collectAllResults(online));
                    actualResults.removeIf(stack -> stack == null || stack.isEmpty());
                    for (ItemStack result : actualResults) addToVirtualInventory(result);

                    IBatchDelegate.ExpectedProduction expected = currentDelegate.getExpectedProduction();
                    int actualCount = countMatchingProduction(actualResults, expected);
                    if (expected != null && expected.count() > actualCount) {
                        RSIntegrationMod.LOGGER.warn(ctx.format(
                                "Craft output partially extracted: recipe={} delegate={} expected={} actual={}"),
                                steps.get(currentStepIdx).recipeId(), currentDelegate.getClass().getSimpleName(),
                                expected.count(), actualCount);
                        // Current inputs were consumed. Preserve real residual output
                        // plus earlier settled intermediates, but never refund this step.
                        snapshotCommittedVirtual();
                        ledger.reset();
                        abortWithoutRefund("Craft output was externally extracted (expected "
                                + expected.count() + ", collected " + actualCount + ")",
                                Component.translatable("rsi.async.error.output_extracted",
                                        expected.count(), actualCount));
                        return true;
                    }

                    // World-output delegates use a separate capture declaration and
                    // may opt out of count comparison while still failing closed.
                    ItemStack expectedWorld = currentDelegate.getExpectedOutput();
                    if (expected == null && expectedWorld != null && !expectedWorld.isEmpty()
                            && actualResults.isEmpty()) {
                        snapshotCommittedVirtual();
                        ledger.reset();
                        abortWithoutRefund("Expected craft output was not captured: "
                                + steps.get(currentStepIdx).recipeId(),
                                Component.translatable("rsi.async.error.output_extracted", 1, 0));
                        return true;
                    }

                    if (currentDelegate instanceof GenericBatchDelegate gbd) {
                        for (ItemStack secondary : gbd.getPendingSecondary()) addToVirtualInventory(secondary);
                    } else if (!currentDelegate.collectsPhysicalSecondaryOutputs()
                            && !(currentDelegate instanceof ParallelCraftGroup)) {
                        ServerLevel overworld = server.overworld();
                        if (overworld == null) return true;
                        Recipe<?> mbRecipe = overworld.getRecipeManager()
                                .byKey(steps.get(currentStepIdx).recipeId()).orElse(null);
                        if (mbRecipe != null) {
                            for (ItemStack secondary : ModRecipeHandlers.tryGetSecondaryOutputs(
                                    mbRecipe, overworld.registryAccess())) {
                                addToVirtualInventory(secondary.copy());
                            }
                        }
                    }
                    boolean parallelGroup = currentDelegate instanceof ParallelCraftGroup;
                    if (parallelGroup) {
                        ParallelCraftGroup group = (ParallelCraftGroup) currentDelegate;
                        if (group.getCompletedOperations() != group.getTotalOperations()) {
                            abort("Parallel group completed with missing operations");
                            return true;
                        }
                        machineCount = group.getChildCount();
                    }
                    try {
                        currentDelegate.onBatchFinished(online);
                    } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFinished error"), fe);
                    }
                    currentDelegate = null;
                    waitTicks = 0;
                    ledger.reset();
                    if (parallelGroup) {
                        stepRemaining = 0;
                    } else {
                        stepRemaining -= machineCount;
                    }
                    if (stepRemaining <= 0) currentStepIdx++;
                    state = State.EXECUTING;
                    snapshotCommittedVirtual();
                    RSIntegrationMod.LOGGER.debug(ctx.format("WAITING_MOD → EXECUTING (remaining={})"), stepRemaining);
                    return false;
                }

                int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
                if (waitTicks > timeoutTicks) {
                    if (currentDelegate instanceof ParallelCraftGroup group && group.isDraining()) {
                        // Keep draining beyond the normal no-progress timeout, but retain
                        // a hard upper bound so a permanently stuck machine cannot pin
                        // the chain and its reservations forever.
                        int drainLimit = Math.max(timeoutTicks * 4, 20 * 60);
                        if (drainingTicks > drainLimit) {
                            abort("Timeout draining in-flight parallel crafts");
                            return true;
                        }
                        RSIntegrationMod.LOGGER.warn(ctx.format(
                                "Parallel group still draining after {} ticks (hard limit {})"),
                                drainingTicks, drainLimit);
                        waitTicks = 0;
                        return false;
                    }
                    abort("Timeout waiting for craft completion");
                    return true;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error polling craft completion"), e);
                abort("Internal error during craft polling");
                return true;
            }
            return false;
        }

        // All done
        if (currentStepIdx >= steps.size()) {
            state = State.COMPLETING;
            finish(online);
            return true;
        }

        // Execute next step(s)
        CraftingResolver.ResolutionStep step = steps.get(currentStepIdx);
        if (step.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP)) {
            if (!startEarthHeartTaint(online, step.executions())) return true;
            return false;
        }
        if (step.modType() == ModType.GENERIC) {
            currentStepIdx = executeVanillaBatch(currentStepIdx, online);
            if (state == State.ABORTED) return true;
            if (!ledger.isCommitted() && !ledger.commit(network, online)) {
                abort("Commit failed after vanilla batch");
                return true;
            }
            ledger.reset();
            // Settled boundary: batch inputs committed, products in virtualInventory.
            snapshotCommittedVirtual();
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
            // Settled boundary: step's inputs are committed and any materials
            // pulled from virtualInventory have been removed by pre-reserve. If
            // the physical craft later times out, the product isn't here yet, so
            // rolling back to this baseline delivers only the truly-owed items.
            snapshotCommittedVirtual();
            Diagnostics.record(Diagnostics.Category.CHAIN_STATE,
                    "EXECUTING→WAITING_MOD step=" + step.recipeId(),
                    step.recipeId(), step.modType());
            RSIntegrationMod.LOGGER.debug(ctx.format("EXECUTING → WAITING_MOD for step {}"),
                    step.recipeId());
            waitTicks = 0;
        }
        return false;
    }

    // ── graph-backed tick (serial mode) ───────────────────────────

    private boolean tickGraph(ServerPlayer online) {
        if (graphScheduler == null) {
            abort("Graph scheduler is null");
            return true;
        }

        // First tick
        if (state == State.PENDING) {
            RSIntegrationMod.LOGGER.debug(ctx.format("PENDING → EXECUTING (graph, {} nodes)"),
                    graph.topologicalOrder().size());
            state = State.EXECUTING;
            Diagnostics.record(Diagnostics.Category.CHAIN_STATE,
                    "PENDING→EXECUTING graph nodes=" + graph.topologicalOrder().size());
        }

        // Observe running delegate
        if (currentDelegate != null && currentGraphNode != null) {
            waitTicks++;
            try {
                if (currentDelegate instanceof ParallelCraftGroup group && group.isDraining()) {
                    drainingTicks++;
                } else {
                    drainingTicks = 0;
                }

                if (currentDelegate instanceof ParallelCraftGroup group) {
                    List<ItemStack> settled = group.drainSettledResults();
                    if (!settled.isEmpty()) {
                        for (ItemStack result : settled) addToVirtualInventory(result);
                        snapshotCommittedVirtual();
                        waitTicks = 0;
                    }
                    List<ItemStack> queued = group.drainQueuedMaterialsForRecovery();
                    if (!queued.isEmpty()) {
                        for (ItemStack m : queued) addToVirtualInventory(m);
                        snapshotCommittedVirtual();
                    }
                }

                IBatchDelegate.CraftObservation observation = currentDelegate.observeCraft(online.serverLevel());
                if (observation.phase() == IBatchDelegate.CraftPhase.FAILED) {
                    abort("Machine craft failed: " + observation.detail());
                    return true;
                }

                if (observation.phase() == IBatchDelegate.CraftPhase.DONE) {
                    // Collect results
                    List<ItemStack> actual = new ArrayList<>(disarmOutputCapture());
                    actual.addAll(currentDelegate.collectAllResults(online));
                    actual.removeIf(s -> s == null || s.isEmpty());
                    for (ItemStack result : actual) addToVirtualInventory(result);

                    // External extraction check
                    IBatchDelegate.ExpectedProduction expected = currentDelegate.getExpectedProduction();
                    int actualCount = countMatchingProduction(actual, expected);
                    if (expected != null && expected.count() > actualCount) {
                        snapshotCommittedVirtual();
                        ledger.reset();
                        abortWithoutRefund("Craft output was externally extracted");
                        return true;
                    }
                    ItemStack expectedWorld = currentDelegate.getExpectedOutput();
                    if (expected == null && expectedWorld != null && !expectedWorld.isEmpty()
                            && actual.isEmpty()) {
                        snapshotCommittedVirtual();
                        ledger.reset();
                        abortWithoutRefund("Expected craft output was not captured");
                        return true;
                    }

                    // Secondary outputs
                    if (!(currentDelegate instanceof ParallelCraftGroup)
                            && !currentDelegate.collectsPhysicalSecondaryOutputs()) {
                        ServerLevel overworld = server.overworld();
                        if (overworld != null) {
                            Recipe<?> mbRecipe = overworld.getRecipeManager()
                                    .byKey(steps.get(stepIndex(currentGraphNode)).recipeId()).orElse(null);
                            if (mbRecipe != null) {
                                for (ItemStack s : ModRecipeHandlers.tryGetSecondaryOutputs(
                                        mbRecipe, overworld.registryAccess())) {
                                    addToVirtualInventory(s.copy());
                                }
                            }
                        }
                    }

                    try { currentDelegate.onBatchFinished(online); }
                    catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFinished error"), fe);
                    }

                    // Settle node in scheduler
                    graphScheduler.succeed(currentGraphNode);
                    currentDelegate = null;
                    currentGraphNode = null;
                    waitTicks = 0;
                    ledger.reset();
                    stepRemaining = 0;
                    snapshotCommittedVirtual();
                    RSIntegrationMod.LOGGER.debug(ctx.format("Graph node succeeded: {}"), currentGraphNode);
                } else {
                    // Timeout
                    int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
                    if (waitTicks > timeoutTicks) {
                        if (currentDelegate instanceof ParallelCraftGroup group && group.isDraining()) {
                            int drainLimit = Math.max(timeoutTicks * 4, 20 * 60);
                            if (drainingTicks > drainLimit) {
                                abort("Timeout draining in-flight parallel crafts");
                                return true;
                            }
                            waitTicks = 0;
                            return false;
                        }
                        abort("Timeout waiting for craft completion");
                        return true;
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error polling craft completion"), e);
                abort("Internal error during craft polling");
                return true;
            }
            return false;
        }

        // All nodes completed?
        if (graphScheduler.allSucceeded()) {
            state = State.COMPLETING;
            finish(online);
            return true;
        }

        // Claim next ready node
        currentGraphNode = claimNextReadyNode();
        if (currentGraphNode == null) {
            if (graphScheduler.isStopping()) {
                abort("Graph scheduler stopped — no ready nodes available");
                return true;
            }
            // No ready nodes but not stopping — waiting for graph consistency
            return false;
        }

        // Execute the claimed node
        int idx = stepIndex(currentGraphNode);
        currentStepIdx = idx;
        CraftingResolver.ResolutionStep step = steps.get(idx);

        if (step.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP)) {
            if (!startEarthHeartTaint(online, step.executions())) return true;
            graphScheduler.succeed(currentGraphNode);
            currentGraphNode = null;
            return false;
        }

        if (step.modType() == ModType.GENERIC) {
            int nextIdx = executeVanillaBatch(idx, online);
            if (state == State.ABORTED) return true;
            if (!ledger.isCommitted() && !ledger.commit(network, online)) {
                abort("Commit failed after vanilla batch");
                return true;
            }
            ledger.reset();
            snapshotCommittedVirtual();
            // Settle all vanilla nodes that were processed
            for (int i = idx; i < nextIdx; i++) {
                graphScheduler.succeed(graph.topologicalOrder().get(i));
            }
            currentGraphNode = null;
            currentStepIdx = nextIdx;
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
            snapshotCommittedVirtual();
            Diagnostics.record(Diagnostics.Category.CHAIN_STATE,
                    "EXECUTING→WAITING_MOD step=" + step.recipeId(),
                    step.recipeId(), step.modType());
            RSIntegrationMod.LOGGER.debug(ctx.format("EXECUTING → WAITING_MOD (graph) for step {}"),
                    step.recipeId());
            waitTicks = 0;
        }
        return false;
    }

    @Nullable
    private NodeId claimNextReadyNode() {
        java.util.List<NodeId> ready = graphScheduler.claimReady(1);
        return ready.isEmpty() ? null : ready.get(0);
    }

    private int stepIndex(NodeId nodeId) {
        for (int i = 0; i < graph.topologicalOrder().size(); i++) {
            if (graph.topologicalOrder().get(i).equals(nodeId)) return i;
        }
        throw new IllegalArgumentException("Unknown graph node " + nodeId);
    }

    public boolean isDone() { return state == State.COMPLETED || state == State.ABORTED; }
    public boolean isAborted() { return state == State.ABORTED; }
    public State state() { return state; }
    public String abortReason() { return abortReason; }
    /** @return the stable identity of this craft run */
    public UUID getCraftId() { return craftId; }
    /** @return the player's UUID (migration from stale ServerPlayer reference) */
    public UUID getPlayerId() { return playerId; }
    public int currentStep() { return currentStepIdx; }
    public int stepsCount() { return steps.size(); }
    public ExtractionLedger ledger() { return ledger; }
    public List<ItemStack> virtualInventory() { return virtualInventory; }

    public boolean belongsTo(UUID playerId) {
        return this.playerId.equals(playerId);
    }

    private boolean startEarthHeartTaint(ServerPlayer player, int count) {
        if (!hasEquippedCursedRing(player)) {
            abort("Earth Heart tainting requires an equipped Cursed Ring", Component.translatable("rsi.async.earth_heart.error.cursed_ring_required"));
            return false;
        }
        int slot = player.getInventory().getFreeSlot();
        if (slot < 0 || slot >= player.getInventory().items.size()) {
            abort("Earth Heart tainting requires an empty main-inventory slot", Component.translatable("rsi.async.earth_heart.error.empty_slot_required"));
            return false;
        }
        ResourceLocation heartId = new ResourceLocation("enigmaticlegacy", "earth_heart");
        ItemStack heart = ItemStack.EMPTY;
        for (ItemStack vi : virtualInventory) {
            if (heartId.equals(ForgeRegistries.ITEMS.getKey(vi.getItem()))
                    && (vi.getTag() == null || !vi.getTag().getBoolean("isTainted"))) {
                heart = vi.split(1);
                break;
            }
        }
        if (heart.isEmpty()) {
            abort("The crafted Earth Heart was not available for tainting", Component.translatable("rsi.async.earth_heart.error.source_missing"));
            return false;
        }
        player.getInventory().items.set(slot, heart);
        player.getInventory().setChanged();
        this.taintSlot = slot;
        this.taintRemaining = Math.max(1, count);
        this.taintWaitTicks = 0;
        this.state = State.WAITING_PLAYER_TRANSFORMATION;
        // Heart is now physically in the player's slot, not virtualInventory —
        // snapshot so an abort mid-taint doesn't re-mint it into the network
        // while it also sits in the player's inventory.
        snapshotCommittedVirtual();
        player.sendSystemMessage(Component.translatable("rsi.async.earth_heart.info.waiting"));
        return true;
    }

    private boolean tickEarthHeartTaint(ServerPlayer player) {
        if (++taintWaitTicks > 100) {
            abortWithoutRefund("Earth Heart tainting timed out; the heart remains in your inventory", Component.translatable("rsi.async.earth_heart.error.timeout"));
            return true;
        }
        if (taintSlot < 0 || taintSlot >= player.getInventory().items.size()) {
            abortWithoutRefund("Earth Heart taint slot became invalid", Component.translatable("rsi.async.earth_heart.error.slot_invalid"));
            return true;
        }
        ItemStack stack = player.getInventory().items.get(taintSlot);
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (!new ResourceLocation("enigmaticlegacy", "earth_heart").equals(id)) {
            abortWithoutRefund("Earth Heart was moved; it remains with the player", Component.translatable("rsi.async.earth_heart.error.moved"));
            return true;
        }
        if (stack.getTag() == null || !stack.getTag().getBoolean("isTainted")) return false;

        ItemStack recovered = stack.split(1);
        if (stack.isEmpty()) player.getInventory().items.set(taintSlot, ItemStack.EMPTY);
        player.getInventory().setChanged();
        addToVirtualInventory(recovered);
        taintRemaining--;
        taintSlot = -1;
        if (taintRemaining > 0) {
            state = State.EXECUTING;
            return !startEarthHeartTaint(player, taintRemaining);
        }
        currentStepIdx++;
        state = State.EXECUTING;
        // Tainted heart is back in virtualInventory and this taint step is done —
        // settled boundary owed to the player on any later abort.
        snapshotCommittedVirtual();
        MaterialSources.invalidateFor(player);
        return false;
    }

    private static boolean hasEquippedCursedRing(ServerPlayer player) {
        try {
            var optional = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (optional.isEmpty()) return false;
            var handler = optional.get().getEquippedCurios();
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack stack = handler.getStackInSlot(i);
                if (new ResourceLocation("enigmaticlegacy", "cursed_ring")
                        .equals(ForgeRegistries.ITEMS.getKey(stack.getItem()))) return true;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Failed to inspect Curios for Cursed Ring", e);
        }
        return false;
    }

    // ── vanilla batch execution ──────────────────────────────────

    /**
     * Execute consecutive vanilla steps synchronously in one tick.
     * Returns the index of the first non-vanilla step (or steps.size()).
     */
    private int executeVanillaBatch(int startIdx, ServerPlayer online) {
        List<CraftingResolver.ResolutionStep> vanillaSteps = new ArrayList<>();
        int i = startIdx;
        while (i < steps.size() && steps.get(i).modType() == ModType.GENERIC
                && !steps.get(i).recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP)) {
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

    private record MachineIdentity(ResourceLocation dimension, long packedPos, ModType modType) {}

    static List<BoundMachine> deduplicateMachines(List<BoundMachine> machines) {
        java.util.LinkedHashMap<MachineIdentity, BoundMachine> distinct = new java.util.LinkedHashMap<>();
        for (BoundMachine machine : machines) {
            MachineIdentity identity = new MachineIdentity(
                    machine.dim(), machine.pos().asLong(), machine.type());
            distinct.putIfAbsent(identity, machine);
        }
        return new ArrayList<>(distinct.values());
    }

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
                        "rsi.async.error.wrong_machine_type", step.recipeId(), totalForMod));
            } else {
                online.sendSystemMessage(Component.translatable(
                        "rsi.async.error.no_machine_bound", step.recipeId()));
            }
            return null;
        }

        // Duplicate bindings must never create multiple workers for one logical machine.
        machines = deduplicateMachines(machines);

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
            // Parallel dispatch failed. If it already committed materials, we must
            // NOT fall through to the single-machine path (that would pre-reserve
            // and extract a second time). Return null so tick() aborts, which
            // refunds the committed ledger and recovers virtualInventory. Only a
            // clean pre-reservation failure is safe to retry single-machine.
            if (ledger.isCommitted()) {
                RSIntegrationMod.LOGGER.warn(ctx.format("[LB] parallel failed after commit — aborting instead of single-machine fallback"));
                return null;
            }
            // Clean failure: undo any pre-reserve drain of virtualInventory and
            // clear stale reservations before retrying via single-machine.
            restoreVirtualFromCommitted();
            if (ledger.state() != ExtractionLedger.State.IDLE) {
                ledger.reset();
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
                        applyTargetOutput(abd);
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
                if (delegate instanceof AbstractBatchDelegate abd) {
                    abd.useSharedLedger(ledger);
                }
                armOutputCapture(delegate, online);
                if (!delegate.tryStartWithMaterials(online, materials, ledger)) {
                    List<ItemStack> escaped = disarmOutputCapture();
                    if (!escaped.isEmpty()) {
                        RSIntegrationMod.LOGGER.error(ctx.format(
                                "Delegate reported start failure after producing {} captured output(s); preserving output and suppressing input refund"),
                                escaped.size());
                        for (ItemStack stack : escaped) addToVirtualInventory(stack);
                        ledger.reset();
                        // The physical machine produced real output despite its start
                        // method returning false. Treat the step as started so the
                        // chain owns and delivers that output exactly once; aborting
                        // here would clear virtualInventory and either lose it or
                        // combine an input refund with an already-produced result.
                        return delegate;
                    }
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
                // Private-ledger path: tryStartSingleCraft's ensureMaterialAvailable
                // only sees network + player inventory, NOT virtualInventory. In a
                // multi-step chain, intermediate products from earlier steps live in
                // virtualInventory and would be invisible here — the craft fails even
                // though the material exists (e.g. CrockPot category filler / WR
                // arcane iterator as a mid-chain step: works alone, fails as a
                // dependency). Flush them into the network first so this step can
                // consume them, then re-snapshot committedVirtual to empty: the
                // products are now network-owned, so recoverCommittedVirtual must NOT
                // re-deliver them on abort (that would duplicate). The ledger refund
                // on abort returns whatever this step consumed back to the network.
                if (network != null && !virtualInventory.isEmpty()) {
                    flushVirtualInventory(online);
                    snapshotCommittedVirtual(); // virtualInventory now empty → empty snapshot
                }
                armOutputCapture(delegate, online);
                if (!delegate.tryStartSingleCraft(online, ledger)) {
                    List<ItemStack> escaped = disarmOutputCapture();
                    if (!escaped.isEmpty()) {
                        RSIntegrationMod.LOGGER.error(ctx.format(
                                "Delegate reported single-craft start failure after producing {} captured output(s); preserving output and suppressing input refund"),
                                escaped.size());
                        for (ItemStack stack : escaped) addToVirtualInventory(stack);
                        ledger.reset();
                        return delegate;
                    }
                    RSIntegrationMod.LOGGER.warn(ctx.format("Delegate tryStartSingleCraft failed for {}"),
                            step.recipeId());
                    try { delegate.onBatchFailed(online, "tryStartSingleCraft failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during tryStartSingleCraft cleanup"), fe);
}
                    return null;
                }
            }
        } catch (Exception e) {
            List<ItemStack> escaped = disarmOutputCapture();
            if (!escaped.isEmpty()) {
                RSIntegrationMod.LOGGER.error(ctx.format(
                        "Delegate threw after producing {} captured output(s); preserving output and suppressing input refund"),
                        escaped.size(), e);
                for (ItemStack stack : escaped) addToVirtualInventory(stack);
                ledger.reset();
                return delegate;
            }
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
                if (delegate instanceof AbstractBatchDelegate abd) {
                    abd.useSharedLedger(ledger);
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

        // Apply the same interaction protection gate as the single-machine path.
        List<BoundMachine> permitted = new ArrayList<>();
        for (BoundMachine machine : available) {
            try {
                ResourceKey<Level> dimKey = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, machine.dim());
                ServerLevel machineLevel = server.getLevel(dimKey);
                if (machineLevel != null
                        && ProtectionChecker.canInteract(online, machineLevel, machine.pos())) {
                    permitted.add(machine);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn(ctx.format("Parallel protection check failed at {}"),
                        machine.pos(), e);
            }
        }
        available = permitted;
        if (available.size() < 2) return null;

        // Do not start more workers than remaining operations.
        int cap = Math.min(step.executions(), stepRemaining);
        if (available.size() > cap) {
            available = new ArrayList<>(available.subList(0, cap));
        }

        // Build a parallel group — constructor internally creates and validates
        // one delegate per machine
        ParallelCraftGroup group = new ParallelCraftGroup(available, step.modType(),
                step.recipeId(), online, stepRemaining);
        if (!group.validateAndInit(online, step.recipeId(), null, BlockPos.ZERO)) {
            RSIntegrationMod.LOGGER.debug(ctx.format("Parallel group empty — all children failed validateAndInit"));
            return null;
        }

        this.machineCount = group.getChildCount();
        group.setMachineServer(server);
        if (targetOutput != null && isPrimaryStep(currentStepIdx)) {
            group.setTargetOutput(targetOutput);
        }
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
            List<IngredientSpec> operationSpecs = group instanceof ParallelCraftGroup parallel
                    ? parallel.getOperationMaterials() : null;
            List<IngredientSpec> specs = operationSpecs != null ? operationSpecs : group.getRequiredMaterials();
            if (specs != null && !specs.isEmpty()) {
                List<ItemStack> materials;
                if (operationSpecs != null && !operationSpecs.isEmpty()
                        && group instanceof ParallelCraftGroup parallel) {
                    List<ReservedOperation> reserved = preReserveParallelOperations(
                            operationSpecs, stepRemaining, online);
                    if (reserved == null) {
                        materials = null;
                    } else {
                        materials = new ArrayList<>();
                        List<ExtractionLedger.ReservationToken> tokens = new ArrayList<>();
                        List<List<ItemStack>> virtualDebits = new ArrayList<>();
                        for (ReservedOperation operation : reserved) {
                            materials.addAll(copyStacksKeepingEmpty(operation.materials()));
                            tokens.add(operation.token());
                            virtualDebits.add(copyStacks(operation.virtualDebits()));
                        }
                        parallel.setReservationTokens(tokens);
                        parallel.setVirtualDebits(virtualDebits);
                    }
                } else {
                    materials = preReserveStepMaterials(specs, online);
                }
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

    private record ReservedOperation(List<ItemStack> materials,
                                     ExtractionLedger.ReservationToken token,
                                     List<ItemStack> virtualDebits) {}

    private List<ReservedOperation> preReserveParallelOperations(
            List<IngredientSpec> specs, int operationCount, ServerPlayer online) {
        List<ItemStack> virtualSnapshot = copyStacks(virtualInventory);
        List<ReservedOperation> reservations = new ArrayList<>();
        for (int operation = 0; operation < operationCount; operation++) {
            int mark = ledger.reservationMark();
            List<ItemStack> virtualDebits = new ArrayList<>();
            List<ItemStack> materials = preReserveStepMaterials(specs, online, virtualDebits);
            if (materials == null) {
                ledger.reset();
                restoreVirtualSnapshot(virtualSnapshot);
                return null;
            }
            ExtractionLedger.ReservationToken token = ledger.tokenSince(mark);
            reservations.add(new ReservedOperation(materials, token, List.copyOf(virtualDebits)));
        }
        return List.copyOf(reservations);
    }

    private static List<ItemStack> copyStacksKeepingEmpty(List<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            copies.add(stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
        return copies;
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) copies.add(stack.copy());
        }
        return copies;
    }

    private void restoreVirtualSnapshot(List<ItemStack> snapshot) {
        virtualInventory.clear();
        virtualInventory.addAll(copyStacks(snapshot));
    }

    private List<ItemStack> preReserveStepMaterials(List<IngredientSpec> specs, ServerPlayer online) {
        return preReserveStepMaterials(specs, online, null);
    }

    private List<ItemStack> preReserveStepMaterials(List<IngredientSpec> specs, ServerPlayer online,
                                                    @Nullable List<ItemStack> virtualDebits) {
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
                    if (virtualDebits != null) virtualDebits.add(taken.copy());
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

    // ── output capture (magnet protection) ───────────────────────

    /**
     * Arm {@link CraftOutputInterceptor} only for delegates that explicitly
     * declare a world-spawned output. Slot-based machines must be collected from
     * their output slot and therefore leave {@link IBatchDelegate#getExpectedOutput()}
     * null.
     */
    private void armOutputCapture(IBatchDelegate delegate, ServerPlayer online) {
        // Slot-based machines expose no expected world output. Arming a broad
        // position-only capture for them can consume unrelated newborn entities
        // (for example an ingredient remainder ejected above the machine), then
        // suppress collectResult() because the captured list is non-empty.
        ItemStack expected = delegate.getExpectedOutput();
        if (expected == null || expected.isEmpty()) return;

        AABB region = delegate.getOutputCaptureRegion();
        if (region == null) return;
        BlockPos pos = delegate.getMachinePos();
        if (pos == null) return;

        ResourceLocation dimLoc = (delegate instanceof AbstractBatchDelegate abd) ? abd.getMachineDim() : null;
        ResourceKey<Level> dim = dimLoc != null
                ? ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimLoc)
                : online.level().dimension();

        this.captureHandle = CraftOutputInterceptor.arm(dim, region, expected);
    }

    /** Tear down the active capture zone and return whatever it grabbed. */
    private List<ItemStack> disarmOutputCapture() {
        CraftOutputInterceptor.CaptureHandle handle = captureHandle;
        captureHandle = null;
        return handle == null ? List.of() : handle.drainAndClose();
    }

    /**
     * On abort, hand any already-captured product to the player / network rather
     * than let the cancelled entity vanish. Rare (output usually means success),
     * but avoids item loss in a timeout/spawn race.
     */
    private void recoverCapturedOutputs(ServerPlayer online) {
        for (ItemStack s : disarmOutputCapture()) {
            if (s.isEmpty()) continue;
            if (online != null) {
                safeGiveToPlayer(online, s);
            } else {
                insertOrDropAtSpawn(s);
            }
        }
    }

    static int countMatchingProduction(List<ItemStack> results,
                                       @Nullable IBatchDelegate.ExpectedProduction expected) {
        if (expected == null || expected.count() <= 0) return 0;
        int count = 0;
        for (ItemStack stack : results) {
            if (stack != null && !stack.isEmpty()
                    && ItemStack.isSameItem(stack, expected.item())) {
                count = count > Integer.MAX_VALUE - stack.getCount()
                        ? Integer.MAX_VALUE : count + stack.getCount();
            }
        }
        return count;
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
     * Restore {@link #virtualInventory} to the last settled baseline WITHOUT
     * flushing — used to undo a failed attempt (e.g. parallel dispatch that
     * pre-reserved from virtualInventory then failed) before retrying via a
     * different path. Safe because the baseline equals virtualInventory at every
     * step boundary, so nothing owed is dropped.
     */
    private void restoreVirtualFromCommitted() {
        virtualInventory.clear();
        for (ItemStack vi : committedVirtual) {
            if (!vi.isEmpty()) virtualInventory.add(vi.copy());
        }
    }

    /**
     * Capture the current {@link #virtualInventory} as the settled-commit
     * baseline. Call ONLY once a step's inputs are irreversibly committed and its
     * products (if any) are already in {@code virtualInventory}, and any materials
     * pulled from {@code virtualInventory} into a physical machine have already
     * been removed. On abort we roll back to this baseline and flush it.
     */
    private void snapshotCommittedVirtual() {
        committedVirtual.clear();
        for (ItemStack vi : virtualInventory) {
            if (!vi.isEmpty()) committedVirtual.add(vi.copy());
        }
    }

    /**
     * On abort, replace the (possibly polluted) live inventory with the settled
     * baseline and deliver it. Products from rolled-back reservations are dropped;
     * products owed to the player (backed by consumed inputs) are inserted into
     * the network, given to the player, or dropped at spawn — never silently lost.
     * Runs after the ledger refund and only once state is already ABORTED, so it
     * cannot re-enter abort() other than via the drop-throttle guard.
     */
    private void recoverCommittedVirtual(@Nullable ServerPlayer online) {
        virtualInventory.clear();
        for (ItemStack owed : committedVirtual) {
            if (owed.isEmpty()) continue;
            if (online != null) {
                ItemStack leftover = owed.copy();
                if (network != null) {
                    leftover = network.insertItem(owed.copy(), owed.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    var tracker = network.getItemStorageTracker();
                    if (tracker != null) tracker.changed(online, owed.copy());
                }
                if (!leftover.isEmpty()) safeGiveToPlayer(online, leftover);
            } else if (!insertOrDropAtSpawn(owed.copy())) {
                // Drop throttle tripped (network full/absent, player offline,
                // >20 drops this chain). insertOrDropAtSpawn already logged the
                // discard. Do NOT clear the rest silently — surface it as CRITICAL
                // so the docstring's "never silently lost" promise isn't a lie.
                RSIntegrationMod.LOGGER.error(ctx.format(
                        "CRITICAL: drop throttle tripped during committed-product recovery; "
                        + "remaining owed products cannot be delivered for player {}"), playerId);
            }
        }
        committedVirtual.clear();
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
        terminalListeners.fireOnce();
    }

    /** Register an additive completion listener. Late listeners run immediately. */
    public void onDone(@Nullable Runnable callback) {
        terminalListeners.add(callback);
    }

    /** How many machines produced output this chain run (1 for single machine, N for parallel). */
    public int getMachineCount() {
        return machineCount;
    }

    /** Abort after a physical machine consumed inputs but its output escaped.
     * Refunding here would duplicate the escaped result. */
    private void abortWithoutRefund(String reason) {
        abortWithoutRefund(reason, Component.literal(reason));
    }

    private void abortWithoutRefund(String reason, Component userReason) {
        if (state == State.ABORTED || state == State.COMPLETED) return;
        ServerPlayer online = resolvePlayer();
        state = State.ABORTED;
        abortReason = reason;
        if (currentDelegate != null) {
            try {
                currentDelegate.onBatchFailed(online, reason);
                if (currentDelegate instanceof ParallelCraftGroup group) {
                    for (ItemStack result : group.drainSettledResults()) addToVirtualInventory(result);
                    for (ItemStack material : group.drainQueuedMaterialsForRecovery()) {
                        addToVirtualInventory(material);
                    }
                    snapshotCommittedVirtual();
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error in no-refund cleanup"), e);
            }
            currentDelegate = null;
        }
        disarmOutputCapture();
        // Recover only earlier settled products. The current step's inputs were
        // consumed with its output escaped/lost — deliberately not refunded here
        // to avoid duplicating an output that may already be in the player's
        // inventory. committedVirtual excludes that in-flight step.
        recoverCommittedVirtual(online);
        ledger.close();
        if (online != null) online.sendSystemMessage(Component.translatable("rsi.async.chain_aborted", userReason));
        fireOnDone();
    }

    public void abort(String reason) {
        abort(reason, Component.literal(reason));
    }

    private void abort(String reason, Component userReason) {
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
                if (currentDelegate instanceof ParallelCraftGroup group) {
                    for (ItemStack result : group.drainSettledResults()) addToVirtualInventory(result);
                    for (ItemStack material : group.drainQueuedMaterialsForRecovery()) {
                        addToVirtualInventory(material);
                    }
                    snapshotCommittedVirtual();
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error in onBatchFailed"), e);
            }
            currentDelegate = null;
        }
        recoverCapturedOutputs(online);

        refundOrRollbackLedger(online);

        // Deliver intermediate products whose backing inputs were already
        // irreversibly consumed by earlier settled steps. committedVirtual is
        // disjoint from both the ledger (this step's reserved inputs) and the
        // captured outputs (this step's world drop), so this never duplicates.
        // In-flight products from the rolled-back reservations are discarded.
        recoverCommittedVirtual(online);

        // Only send message if player is still online
        if (online != null) {
            online.sendSystemMessage(Component.translatable("rsi.async.chain_aborted", userReason));
        }
        ledger.close();
        fireOnDone();
    }

    private void refundOrRollbackLedger(@Nullable ServerPlayer player) {
        try {
            if (ledger.isCommitted()) {
                ledger.refundCommitted(network, player);
            } else {
                ledger.rollback(player);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error(ctx.format("Failed to refund or roll back ledger"), e);
        }
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
                if (currentDelegate instanceof ParallelCraftGroup group) {
                    for (ItemStack result : group.drainSettledResults()) addToVirtualInventory(result);
                    for (ItemStack material : group.drainQueuedMaterialsForRecovery()) {
                        addToVirtualInventory(material);
                    }
                    snapshotCommittedVirtual();
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error in silent delegate cleanup"), e);
            }
            currentDelegate = null;
        }
        recoverCapturedOutputs(null);

        refundOrRollbackLedger(null);

        // Deliver products owed from earlier settled steps (player offline →
        // network insert / drop at spawn). Disjoint from the refunded ledger.
        recoverCommittedVirtual(null);

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
