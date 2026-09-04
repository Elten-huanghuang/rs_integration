package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.compat.ftbquests.ExternalItemProgressBridge;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotBatchDelegate;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyDelegateMode;
import com.huanghuang.rsintegration.mods.embers.KnownCodeSavedData;
import com.huanghuang.rsintegration.util.InsertedStackDelta;
import com.huanghuang.rsintegration.util.TrackedNetworkInsertion;
import com.huanghuang.rsintegration.util.LogSampler;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.batch.CraftProgressPacket;
import com.huanghuang.rsintegration.crafting.batch.CraftProgressDeltaPacket;
import com.huanghuang.rsintegration.crafting.batch.CraftProgressPublisher;
import com.huanghuang.rsintegration.crafting.batch.CraftStartedPacket;
import com.huanghuang.rsintegration.crafting.batch.PreparationMessageScope;
import net.minecraftforge.network.NetworkDirection;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.DagScheduler;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeAdmissionCoordinator;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import com.huanghuang.rsintegration.crafting.graph.GraphConcurrencyPolicy;
import com.huanghuang.rsintegration.crafting.graph.GraphConcurrencyEligibility;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
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
import com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler;
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
import java.util.function.Predicate;

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

    private static final LogSampler GRAPH_RETRY_LOGS = new LogSampler(2_000L);
    private static final int GRAPH_RETRY_MAX_DELAY_TICKS = 40;

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
    /** Backend-neutral storage endpoint; RS is retained only as a compatibility handle. */
    @Nullable
    private final CraftStorageEndpoint storageEndpoint;
    private final List<CraftingResolver.ResolutionStep> steps;
    private final List<ItemStack> virtualInventory = new ArrayList<>();
    /**
     * Snapshot of {@link #virtualInventory} at the last <i>settled</i> commit
     * boundary -i.e. the set of intermediate products whose backing inputs were
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
    private int flatObservedCompletedOperations;
    private int stepRemaining;
    private State state = State.PENDING;
    private int taintSlot = -1;
    private int taintRemaining;
    private int taintWaitTicks;
    private String abortReason = "";
    private TerminationCoordinator.Cause terminalCause;
    private final TerminalListeners terminalListeners;
    private final TerminationService terminationService = new TerminationService();
    private int machineCount = 1;
    private boolean waitingForMachineLease;
    private int machineLeaseWaitTicks;
    @Nullable
    private Component machineStartFailureMessage;
    private int dropsThisChain;
    private boolean dropThrottleTripped;
    private static final int MAX_DROPS_PER_CHAIN = 20;
    /** Final delivery is deliberately cursor-based so a deep chain cannot monopolize one tick. */
    private boolean finalSettlementPrepared;
    private int finalSettlementCursor;

    /**
     * The concrete output the player asked for, captured from the JEI ghost
     * output slot. Applied to the delegate that produces the primary recipe
     * (step 0) so it can distinguish NBT-variant outputs sharing one recipe id
     * (e.g. WR arcane iterator "Curse II" vs "Curse I"). Null when unsupplied.
     */
    @Nullable
    private ItemStack targetOutput;
    private OutputDestination outputDestination = OutputDestination.RS_NETWORK;
    @Nullable
    private MachinePreference machinePreference;

    private record MachinePreference(ResourceLocation recipeId,
                                     MachineSelectionMode mode,
                                     @Nullable ResourceLocation dimension,
                                     @Nullable BlockPos position) {}

    /** Active execution session for the current flat physical-machine step. */
    @Nullable
    private OperationExecutionKernel.Session flatOperationSession;

    /** Legacy capture handle for delegates that do not own a physical machine. */
    @Nullable
    private CraftOutputInterceptor.CaptureHandle captureHandle;

    @Nullable
    private final CraftPlanGraph graph;
    /** True when {@link #graph} includes the requested terminal recipe and its root output. */
    private boolean graphDeclaresFinalOutput;
    @Nullable
    private final DagScheduler graphScheduler;
    private boolean useGraphExecution;
    @Nullable
    private NodeId currentGraphNode;
    @Nullable
    private ConcurrentNodeExecutor graphExecutor;
    private final Map<NodeId, CraftNodeRuntime> nodeRuntimes = new HashMap<>();
    private final Map<NodeId, String> graphFailureDetails = new HashMap<>();
    /** Prevents an unavailable machine/material from being rescanned every tick. */
    private final Map<NodeId, Integer> graphRetryUntilTick = new HashMap<>();
    private final Map<NodeId, Integer> graphRetryAttempts = new HashMap<>();
    private final Map<String, Integer> graphRetryCounts = new HashMap<>();
    @Nullable
    private final MaterialBroker graphMaterials;
    @Nullable
    private final NodeAdmissionCoordinator graphAdmissions;
    private final Map<NodeId, List<MaterialBroker.Request>> graphRequests = new HashMap<>();
    private final Map<NodeId, CraftNode> graphNodes = new HashMap<>();
    private final ProgressWatchdog graphProgressWatchdog;
    private final int graphRunningNodeCap;
    private final int graphDispatchPerTick;
    private final int graphDispatchPerCraft;
    private final OperationBudget craftOperationBudget;
    private final OperationBudget globalOperationBudget;
    private final MachineLeaseRegistry machineLeases;
    private final CaptureLeaseRegistry captureLeases;
    private final OperationResourceCoordinator operationResources;
    private final OperationExecutionKernel operationKernel;
    private int progressTickCounter;
    private boolean waitingForVanillaBudget;
    private final CraftProgressPublisher progressPublisher;
    private int progressSequence;
    @Nullable
    private TerminationCoordinator.Report terminationReport;

    public AsyncCraftChain(UUID playerId, MinecraftServer server, INetwork network,
                           List<CraftingResolver.ResolutionStep> steps) {
        this(UUID.randomUUID(), playerId, server, network, steps, null, null);
    }

    public AsyncCraftChain(UUID playerId, MinecraftServer server, @Nullable INetwork network,
                           @Nullable CraftStorageEndpoint storageEndpoint,
                           List<CraftingResolver.ResolutionStep> steps) {
        this(UUID.randomUUID(), playerId, server, network, steps, null, storageEndpoint);
    }

    public AsyncCraftChain(UUID playerId, MinecraftServer server, INetwork network,
                           CraftPlanGraph graph) {
        this(UUID.randomUUID(), playerId, server, network,
                ExecutionEquivalence.projectFlatSteps(graph), graph, null);
    }

    public AsyncCraftChain(UUID playerId, MinecraftServer server, @Nullable INetwork network,
                           @Nullable CraftStorageEndpoint storageEndpoint,
                           CraftPlanGraph graph) {
        this(UUID.randomUUID(), playerId, server, network,
                ExecutionEquivalence.projectFlatSteps(graph), graph, storageEndpoint);
    }

    public AsyncCraftChain(UUID playerId, MinecraftServer server, INetwork network,
                           CraftPlanGraph graph, CraftingResolver.ResolutionStep terminalStep,
                           int repeatCount) {
        this(playerId, server, network, null, graph, terminalStep, repeatCount);
    }

    public AsyncCraftChain(UUID playerId, MinecraftServer server, @Nullable INetwork network,
                           @Nullable CraftStorageEndpoint storageEndpoint,
                           CraftPlanGraph graph, CraftingResolver.ResolutionStep terminalStep,
                           int repeatCount) {
        this(UUID.randomUUID(), playerId, server, network,
                compatibilitySteps(ExecutionEquivalence.projectFlatSteps(graph),
                        terminalStep, repeatCount), graph, storageEndpoint);
        // The resolver graph describes the materials needed by terminalStep; it
        // does not contain terminalStep itself. Running that graph scheduler would
        // therefore finish after the intermediates and silently skip the requested
        // physical craft. Keep the authoritative projected allocations for plan/UI,
        // but execute this compatibility shape through the flat chain until the
        // terminal operation is represented as a real graph node.
        GraphExecutionPolicy.Decision executionDecision = GraphExecutionPolicy.decide(true,
                terminalStep == null ? null : terminalStep.modType());
        this.graphDeclaresFinalOutput = false;
        this.useGraphExecution = executionDecision.useGraphExecutor();
        RSIntegrationMod.LOGGER.debug(ctx.format(
                "Using {} execution for graph plan: reason={} detail={} terminalStep={}"),
                executionDecision.useGraphExecutor() ? "graph" : "flat",
                executionDecision.reason(), executionDecision.detail(), terminalStep.recipeId());
    }

    AsyncCraftChain(UUID craftId, UUID playerId, MinecraftServer server, INetwork network,
                    List<CraftingResolver.ResolutionStep> steps) {
        this(craftId, playerId, server, network, steps, null, null);
    }

    private AsyncCraftChain(UUID craftId, UUID playerId, MinecraftServer server, INetwork network,
                            List<CraftingResolver.ResolutionStep> steps,
                            @Nullable CraftPlanGraph graph,
                            @Nullable CraftStorageEndpoint storageEndpoint) {
        this.craftId = Objects.requireNonNull(craftId, "craftId");
        this.progressPublisher = new CraftProgressPublisher(craftId);
        this.playerId = playerId;
        this.server = server;
        this.network = network;
        this.storageEndpoint = storageEndpoint != null
                ? storageEndpoint
                : network == null ? null : CraftStorageEndpoints.fromLegacyNetwork(network);
        this.steps = List.copyOf(steps);
        if (graph != null) {
            CraftPlanValidator.validate(graph);
        }
        ResourceLocation primaryRecipe = steps.isEmpty() ? new ResourceLocation("rsintegration", "empty_chain")
                : steps.get(0).recipeId();
        this.ctx = CraftLogContext.create(playerId, primaryRecipe);
        this.terminalListeners = new TerminalListeners(
                error -> RSIntegrationMod.LOGGER.error(ctx.format("onDone callback threw"), error),
                AsyncCraftManager.getInstance()::enqueueCompletion);
        this.ledger.setLogContext(ctx);
        if (this.storageEndpoint != null) this.ledger.setStorageEndpoint(this.storageEndpoint);
        int cap;
        try { cap = RSIntegrationConfig.CRAFTING_MAX_CONCURRENT_GRAPH_NODES.get(); }
        catch (Exception e) { cap = 1; }
        this.graphRunningNodeCap = Math.max(1, cap);
        int dispatchPerTick;
        int dispatchPerCraft;
        try {
            dispatchPerTick = RSIntegrationConfig.CRAFTING_GRAPH_DISPATCH_PER_TICK.get();
            dispatchPerCraft = RSIntegrationConfig.CRAFTING_GRAPH_DISPATCH_PER_CRAFT.get();
        } catch (Exception e) {
            dispatchPerTick = 2;
            dispatchPerCraft = 8192;
        }
        this.graphDispatchPerTick = Math.max(1, dispatchPerTick);
        this.graphDispatchPerCraft = Math.max(1, dispatchPerCraft);
        int operationCap;
        int operationStarts;
        try {
            operationCap = RSIntegrationConfig.CRAFTING_MAX_CONCURRENT_OPERATIONS.get();
            operationStarts = RSIntegrationConfig.CRAFTING_OPERATION_DISPATCH_PER_CRAFT.get();
        } catch (Exception e) {
            operationCap = 4;
            operationStarts = 16384;
        }
        this.craftOperationBudget = new OperationBudget(
                Math.max(1, operationCap), Math.max(1, operationStarts));
        AsyncCraftManager manager = AsyncCraftManager.getInstance();
        this.globalOperationBudget = manager.operationBudget();
        this.machineLeases = manager.machineLeases();
        this.captureLeases = manager.captureLeases();
        this.operationResources = manager.operationResources();
        this.operationKernel = manager.operationKernel();
        int globalTimeoutSeconds;
        try { globalTimeoutSeconds = RSIntegrationConfig.CRAFTING_CHAIN_GLOBAL_TIMEOUT_SECONDS.get(); }
        catch (Exception e) { globalTimeoutSeconds = 900; }
        this.graphProgressWatchdog = new ProgressWatchdog(
                Math.max(1, globalTimeoutSeconds) * 20);
        if (graph != null) {
            this.graph = graph;
            this.graphDeclaresFinalOutput = true;
            this.graphScheduler = new DagScheduler(graph);
            this.graphMaterials = new MaterialBroker();
            this.graphAdmissions = new NodeAdmissionCoordinator(graphScheduler, graphMaterials);
            initialiseGraphMaterialFlow(graph);
            logGraphNodeMappings(graph);
            GraphExecutionPolicy.Decision executionDecision = GraphExecutionPolicy.decide(false,
                    steps.stream().map(CraftingResolver.ResolutionStep::modType).distinct().toList());
            int atomicVanillaLimit = configuredAtomicVanillaGraphLimit();
            int dispatchOperationLimit = configuredOperationsPerDispatch();
            boolean oversizedNode = requiresFlatExecutionForOversizedNode(
                    steps, atomicVanillaLimit, dispatchOperationLimit);
            this.useGraphExecution = executionDecision.useGraphExecutor() && !oversizedNode;
            RSIntegrationMod.LOGGER.debug(ctx.format(
                    "Using {} execution for self-contained graph: reason={} detail={}"),
                    useGraphExecution ? "graph" : "flat",
                    oversizedNode ? "NODE_REQUIRES_TICK_SLICING"
                            : executionDecision.reason(),
                    oversizedNode
                            ? "node exceeds its atomic dispatch limit (vanilla="
                                    + atomicVanillaLimit + ", machine=" + dispatchOperationLimit + ")"
                            : executionDecision.detail());
        } else {
            this.graph = null;
            this.graphDeclaresFinalOutput = false;
            this.graphScheduler = null;
            this.graphMaterials = null;
            this.graphAdmissions = null;
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

    private void initialiseGraphMaterialFlow(CraftPlanGraph plan) {
        for (CraftNode node : plan.nodes()) graphNodes.put(node.id(), node);
        Map<InitialLotKey, Integer> initial = new java.util.LinkedHashMap<>();
        for (MaterialAllocation allocation : plan.allocations()) {
            graphRequests.computeIfAbsent(allocation.consumer().nodeId(), ignored -> new ArrayList<>())
                    .add(new MaterialBroker.Request(allocation.source(), allocation.material(),
                            allocation.quantity()));
            if (allocation.source() instanceof MaterialSource.InitialPool source) {
                initial.merge(new InitialLotKey(source, allocation.material()),
                        allocation.quantity(), Integer::sum);
            }
        }
        if (graphMaterials != null) {
            for (Map.Entry<InitialLotKey, Integer> entry : initial.entrySet()) {
                graphMaterials.publish(entry.getKey().source(), entry.getKey().material(), entry.getValue());
            }
        }
    }

    private record InitialLotKey(MaterialSource.InitialPool source, MaterialKey material) {}

    static List<CraftingResolver.ResolutionStep> compatibilitySteps(
            List<CraftingResolver.ResolutionStep> projected,
            CraftingResolver.ResolutionStep terminalStep,
            int repeatCount) {
        List<CraftingResolver.ResolutionStep> result = new ArrayList<>(projected.size() + 1);
        // A resolver graph built from repeat-expanded root specs already contains
        // scaled intermediate executions. Only the terminal recipe is absent.
        result.addAll(projected);
        CraftingResolver.ResolutionStep terminal = Objects.requireNonNull(terminalStep, "terminalStep");
        int executions = Math.max(1, repeatCount);
        if (terminal.executions() != executions) {
            terminal = new CraftingResolver.ResolutionStep(terminal.recipeId(), terminal.modType(),
                    terminal.recipeTypeId(), terminal.alternativeIds(), terminal.alternativeModTypes(),
                    terminal.inferMode(), executions, terminal.syntheticInput(), terminal.syntheticOutput());
        }
        result.add(terminal);
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

    public void setOutputDestination(@Nullable OutputDestination destination) {
        this.outputDestination = destination == null ? OutputDestination.RS_NETWORK : destination;
    }

    /** Applies only to the target recipe and is matched against server-owned bindings. */
    public void setMachineSelection(ResourceLocation recipeId, MachineSelectionMode mode,
                                    @Nullable ResourceLocation dimension,
                                    @Nullable BlockPos position) {
        if (recipeId == null || mode == null || mode == MachineSelectionMode.AUTO) {
            machinePreference = null;
            return;
        }
        machinePreference = new MachinePreference(recipeId, mode, dimension,
                position == null ? null : position.immutable());
    }

    private List<BoundMachine> applyMachineSelection(
            List<BoundMachine> machines, ResourceLocation recipeId) {
        MachinePreference preference = machinePreference;
        if (preference == null || !preference.recipeId().equals(recipeId)) return machines;
        return routeMachineCandidates(machines, preference.mode(),
                preference.dimension(), preference.position());
    }

    static List<BoundMachine> routeMachineCandidates(
            List<BoundMachine> machines, MachineSelectionMode mode,
            @Nullable ResourceLocation dimension, @Nullable BlockPos position) {
        if (machines == null || machines.isEmpty() || mode == null
                || mode == MachineSelectionMode.AUTO) {
            return machines == null ? List.of() : machines;
        }
        if (dimension == null || position == null) {
            return mode == MachineSelectionMode.EXCLUSIVE ? List.of() : machines;
        }
        BoundMachine selected = machines.stream()
                .filter(machine -> machine.dim().equals(dimension)
                        && machine.pos().equals(position))
                .findFirst().orElse(null);
        if (mode == MachineSelectionMode.EXCLUSIVE) {
            return selected == null ? List.of() : List.of(selected);
        }
        if (selected == null || machines.size() < 2 || machines.get(0) == selected) return machines;
        List<BoundMachine> ordered = new ArrayList<>(machines.size());
        ordered.add(selected);
        for (BoundMachine machine : machines) if (machine != selected) ordered.add(machine);
        return ordered;
    }

    @Nullable
    public ItemStack displayTarget() {
        return targetOutput == null ? null : targetOutput.copy();
    }

    /**
     * True for the step that produces the chain's primary (final) output -i.e.
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

    //  player resolution

    /**
     * Look up the player by UUID. Returns null if the player is offline
     * or the server reference is unavailable.
     */
    private ServerPlayer resolvePlayer() {
        if (server == null) return null;
        return server.getPlayerList().getPlayer(playerId);
    }

    //  tick

    /**
     * Advance the chain by one tick. Returns true when the chain is done
     * (either finished successfully or aborted).
     */
    public boolean tick() {
        VanillaCraftingTickBudget fallback = new VanillaCraftingTickBudget(Integer.MAX_VALUE);
        return tick(fallback.allowance(Integer.MAX_VALUE));
    }

    boolean tick(VanillaCraftingTickBudget.ChainAllowance vanillaAllowance) {
        waitingForVanillaBudget = false;
        if (state == State.ABORTED || state == State.COMPLETED) return true;

        if (state == State.WAITING_PLAYER_TRANSFORMATION) {
            ServerPlayer online = resolvePlayer();
            if (online == null) {
                abortWithoutRefund("Player disconnected while Earth Heart was in inventory", Component.translatable("rsi.async.earth_heart.error.player_disconnected"));
                return true;
            }
            return tickEarthHeartTaint(online);
        }

        // Dynamic player lookup -null means player disconnected
        ServerPlayer online = resolvePlayer();

        // Use graph scheduler when available -serial mode by default
        if (useGraphExecution) {
            if (online == null) {
                abortSilently("Player disconnected");
                return true;
            }
            if (network != null && !network.canRun()) {
                abortSilently("RS controller removed or network invalidated");
                return true;
            }
            return tickGraph(online, vanillaAllowance);
        }

        // First tick transition
        if (state == State.PENDING) {
            RSIntegrationMod.LOGGER.debug(ctx.format("PENDING ->EXECUTING"));
            state = State.EXECUTING;
            Diagnostics.record(Diagnostics.Category.CHAIN_STATE, "PENDING->XECUTING steps=" + steps.size());
            sendStartedPacket(online);
            sendProgressSnapshot(online, buildProgressSnapshot(false));
        }
        if (online == null) {
            abortSilently("Player disconnected");
            return true;
        }

        // Re-validate network each tick -RS controller may have been removed
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
                boolean capturedOutput = hasCapturedOutput();
                IBatchDelegate.CraftObservation observation = currentDelegate.observeCraft(online.serverLevel());
                if (currentDelegate instanceof ParallelCraftGroup group && group.isDraining()) {
                    drainingTicks++;
                } else {
                    drainingTicks = 0;
                }
                if (currentDelegate instanceof ParallelCraftGroup group) {
                    int completed = group.getCompletedOperations();
                    if (completed > flatObservedCompletedOperations) {
                        flatObservedCompletedOperations = completed;
                        waitTicks = 0;
                    }
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
                    if (currentDelegate.failureConsumesInputs(observation)) {
                        List<ItemStack> actualResults = new ArrayList<>(disarmOutputCapture());
                        closeFlatOperationScope();
                        actualResults.addAll(currentDelegate.collectAllResults(online));
                        actualResults.removeIf(stack -> stack == null || stack.isEmpty());
                        for (ItemStack result : actualResults) addToVirtualInventory(result);
                        snapshotCommittedVirtual();
                        ledger.reset();
                        Component failureMessage = currentDelegate.craftFailureMessage(observation);
                        if (failureMessage == null) {
                            failureMessage = Component.translatable(
                                    "rsi.async.abort.machine_craft_failed", observation.detail());
                        }
                        try {
                            currentDelegate.onBatchFinished(online);
                            currentDelegate.releaseReusableMaterials(online);
                        } catch (Exception cleanupFailure) {
                            RSIntegrationMod.LOGGER.error(ctx.format(
                                    "Consumed-failure delegate cleanup failed"), cleanupFailure);
                        }
                        currentDelegate = null;
                        abortWithoutRefund("Machine craft consumed inputs but failed: "
                                + observation.detail(), failureMessage);
                        return true;
                    }
                    abort("Machine craft failed: " + observation.detail(),
                            Component.translatable("rsi.async.abort.machine_craft_failed",
                                    observation.detail()));
                    return true;
                }
                // World-output capture cancels the spawned ItemEntity before the delegate can observe it.
                // Treat a matching captured output as completion so it settles into the RS inventory.
                ItemStack expectedCapturedOutput = currentDelegate.getExpectedOutput();
                boolean capturedWorldOutput = capturedOutput
                        && hasCapturedExpectedCount(expectedCapturedOutput);
                if (observation.phase() == IBatchDelegate.CraftPhase.DONE || capturedWorldOutput) {
                    if (!currentDelegate.validateExecutionContext(online)) {
                        abort("Execution context changed before output publication",
                                Component.translatable("rsi.async.abort.execution_context"));
                        return true;
                    }
                    List<ItemStack> actualResults = new ArrayList<>(disarmOutputCapture());
                    closeFlatOperationScope();
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
                                Component.translatable("rsi.async.abort.output_not_captured",
                                        steps.get(currentStepIdx).recipeId().toString()));
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
                            abort("Parallel group completed with missing operations",
                                    Component.translatable("rsi.async.abort.parallel_incomplete"));
                            return true;
                        }
                        machineCount = group.getChildCount();
                    }
                    try {
                        currentDelegate.onBatchFinished(online);
                        currentDelegate.releaseReusableMaterials(online);
                    } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFinished error"), fe);
                    }
                    currentDelegate = null;
                    waitTicks = 0;
                    flatObservedCompletedOperations = 0;
                    ledger.reset();
                    if (parallelGroup) {
                        stepRemaining = 0;
                    } else {
                        stepRemaining -= machineCount;
                    }
                    if (stepRemaining <= 0) currentStepIdx++;
                    state = State.EXECUTING;
                    snapshotCommittedVirtual();
                    RSIntegrationMod.LOGGER.debug(ctx.format("WAITING_MOD ->EXECUTING (remaining={})"), stepRemaining);
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
                            abort("Timeout draining in-flight parallel crafts",
                                    Component.translatable("rsi.async.abort.parallel_drain_timeout"));
                            return true;
                        }
                        RSIntegrationMod.LOGGER.warn(ctx.format(
                                "Parallel group still draining after {} ticks (hard limit {})"),
                                drainingTicks, drainLimit);
                        waitTicks = 0;
                        return false;
                    }
                    abort("Timeout waiting for craft completion",
                            Component.translatable("rsi.async.abort.craft_timeout"));
                    return true;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error polling craft completion"), e);
                abort("Internal error during craft polling",
                        Component.translatable("rsi.async.abort.poll_error"));
                return true;
            }
            maybeSendProgress(online, false);
            return false;
        }

        // All done
        if (currentStepIdx >= steps.size()) {
            state = State.COMPLETING;
            return finish(online);
        }

        // Execute next step(s)
        CraftingResolver.ResolutionStep step = steps.get(currentStepIdx);
        if (step.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP)) {
            if (!startEarthHeartTaint(online, step.executions())) return true;
            return false;
        }
        if (step.modType() == ModType.GENERIC) {
            int granted = vanillaAllowance.claimUpTo(vanillaAllowance.remaining());
            if (granted <= 0) {
                waitingForVanillaBudget = true;
                maybeSendProgress(online, false);
                return false;
            }
            currentStepIdx = executeVanillaBatch(currentStepIdx, online, granted);
            if (state == State.ABORTED) return true;
            waitingForVanillaBudget = currentStepIdx < steps.size()
                    && steps.get(currentStepIdx).modType() == ModType.GENERIC;
            if (!ledger.isCommitted() && !commitLedger(ledger, online)) {
                abort("Commit failed after vanilla batch",
                        Component.translatable("rsi.async.abort.vanilla_commit_failed"));
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
            flatObservedCompletedOperations = 0;
            if (currentDelegate == null) {
                if (waitingForMachineLease) {
                    machineLeaseWaitTicks++;
                    int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
                    if (machineLeaseWaitTicks > timeoutTicks) {
                        abort("Timeout waiting for an available multi-block machine: " + step.recipeId(),
                                Component.translatable("rsi.async.abort.machine_wait_timeout",
                                        step.recipeId().toString()));
                        return true;
                    }
                    maybeSendProgress(online, false);
                    return false;
                }
                abort("Failed to start multi-block craft: " + step.recipeId(),
                        machineStartFailureMessage != null
                                ? machineStartFailureMessage
                                : Component.translatable("rsi.async.abort.machine_start_failed",
                                        step.recipeId().toString()));
                machineStartFailureMessage = null;
                return true;
            }
            waitingForMachineLease = false;
            machineLeaseWaitTicks = 0;
            machineStartFailureMessage = null;
            state = State.WAITING_MOD;
            // Settled boundary: step's inputs are committed and any materials
            // pulled from virtualInventory have been removed by pre-reserve. If
            // the physical craft later times out, the product isn't here yet, so
            // rolling back to this baseline delivers only the truly-owed items.
            snapshotCommittedVirtual();
            Diagnostics.record(Diagnostics.Category.CHAIN_STATE,
                    "EXECUTING->AITING_MOD step=" + step.recipeId(),
                    step.recipeId(), step.modType());
            RSIntegrationMod.LOGGER.debug(ctx.format("EXECUTING ->WAITING_MOD for step {}"),
                    step.recipeId());
            waitTicks = 0;
        }
        maybeSendProgress(online, false);
        return false;
    }

    //  graph-backed tick (multi-node parallel)

    private boolean tickGraph(ServerPlayer online,
                              VanillaCraftingTickBudget.ChainAllowance vanillaAllowance) {
        if (graphScheduler == null) {
            abort("Graph scheduler is null",
                    Component.translatable("rsi.async.abort.scheduler_missing"));
            return true;
        }

        // First tick -initialise executor
        if (state == State.PENDING) {
            RSIntegrationMod.LOGGER.debug(ctx.format("PENDING ->EXECUTING (graph, {} nodes, cap={})"),
                    graph.topologicalOrder().size(), graphRunningNodeCap);
            state = State.EXECUTING;
            Diagnostics.record(Diagnostics.Category.CHAIN_STATE,
                    "PENDING->XECUTING graph nodes=" + graph.topologicalOrder().size());
            ConcurrentNodeExecutor.AdmissionWorkerFactory graphWorkers =
                    nodeId -> startAdmittedGraphNode(nodeId, online);
            graphExecutor = new ConcurrentNodeExecutor(graphScheduler,
                    graphWorkers, graphRunningNodeCap, this::isNodeExclusive,
                    this::publishIncrementalGraphOutputs, this::completeGraphNode,
                    this::recordGraphRuntimeFailure,
                    graphDispatchPerTick, graphDispatchPerCraft,
                    this::isConcurrentGraphNode);
            sendStartedPacket(online);
            sendProgressSnapshot(online, buildProgressSnapshot(false));
        }

        if (graphExecutor == null) {
            abort("Graph executor not initialised",
                    Component.translatable("rsi.async.abort.executor_missing"));
            return true;
        }

        // Process synchronous GENERIC nodes outside the executor.
        // They complete immediately and don't wait for observation ticks.
        settleReadyVanillaNodes(online, vanillaAllowance);

        // Drive the executor: observe all running, settle completed, dispatch new.
        try {
            graphExecutor.tick();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error(ctx.format("Graph executor tick error"), e);
            abort("Graph executor error: " + e.getMessage(),
                    Component.translatable("rsi.async.abort.executor_error",
                            String.valueOf(e.getMessage())));
            return true;
        }

        // Check for failures
        if (graphScheduler.isStopping() && !graphScheduler.allSucceeded()) {
            if (graphExecutor.runningCount() == 0) {
                NodeId failedNode = graphScheduler.failedNode();
                String detail = failedNode == null ? ""
                        : graphFailureDetails.getOrDefault(failedNode, "");
                if (detail.isEmpty()) {
                    abort("Graph execution failed -no running nodes remain",
                            Component.translatable("rsi.async.abort.graph_stalled"));
                } else {
                    abort("Graph node " + failedNode.value() + " failed: " + detail,
                            Component.translatable("rsi.async.abort.graph_node_failed", detail));
                }
                return true;
            }
        }

        // All nodes succeeded -deliver
        if (graphScheduler.allSucceeded()) {
            state = State.COMPLETING;
            return finish(online);
        }

        // Check after this tick's machine observations and settlements so an
        // output produced on the deadline tick can renew the idle window.
        if (graphProgressWatchdog.tick()) {
            abort("Crafting chain made no progress for "
                    + (graphProgressWatchdog.timeoutTicks() / 20) + "s",
                    Component.translatable("rsi.async.abort.global_timeout",
                            graphProgressWatchdog.timeoutTicks() / 20));
            return true;
        }

        maybeSendProgress(online, false);
        return false;
    }

    /** Execute ready GENERIC nodes synchronously, one graph node at a time. */
    private void settleReadyVanillaNodes(
            ServerPlayer online, VanillaCraftingTickBudget.ChainAllowance vanillaAllowance) {
        while (true) {
            NodeId vanillaNode = findReadyVanillaNode();
            if (vanillaNode == null) break;
            if (graphAdmissions == null) {
                graphScheduler.fail(vanillaNode);
                return;
            }
            NodeAdmissionCoordinator.Candidate candidate = new NodeAdmissionCoordinator.Candidate(
                    vanillaNode, graphRequests.getOrDefault(vanillaNode, List.of()));
            NodeAdmissionCoordinator.Admission admission = graphAdmissions.tryAdmitClaimed(candidate);
            if (admission == null) {
                graphScheduler.releaseClaim(vanillaNode);
                break;
            }

            int idx = stepIndex(vanillaNode);
            CraftingResolver.ResolutionStep step = graphStep(vanillaNode);
            int executions = Math.max(1, step.executions());
            if (!claimVanillaBudgetOrReleaseAdmission(
                    vanillaAllowance, graphAdmissions, admission, executions)) {
                waitingForVanillaBudget = true;
                break;
            }
            currentStepIdx = idx;
            ExtractionLedger nodeLedger = new ExtractionLedger();
            nodeLedger.setLogContext(ctx);
            if (storageEndpoint != null) nodeLedger.setStorageEndpoint(storageEndpoint);
            OperationExecutionKernel.Session operation = operationKernel.prepareLogical();
            MaterialBroker.Checkout checkout = graphMaterials.checkout(admission.materialToken());
            List<ItemStack> operationInventory = new ArrayList<>(checkout.producerStacks());
            boolean initialReserved = true;
            for (ItemStack initial : checkout.initialStacks()) {
                ItemStack reserved = reserveExact(nodeLedger, initial, initial.getCount(), online);
                if (reserved.isEmpty()) {
                    initialReserved = false;
                    break;
                }
                operationInventory.add(reserved);
            }
            if (!initialReserved || !operation.commit(() -> commitLedger(nodeLedger, online))) {
                graphAdmissions.releaseMaterial(admission);
                if (nodeLedger.isCommitted()) refundCommitted(nodeLedger, online);
                else nodeLedger.rollback(online);
                operation.close();
                graphScheduler.releaseClaim(vanillaNode);
                break;
            }
            graphAdmissions.commit(admission);
            boolean executed;
            try {
                executed = operation.tryStart(() -> executeVanillaStepsInline(
                        List.of(step), online, operationInventory, nodeLedger, false));
            } catch (RuntimeException exception) {
                RSIntegrationMod.LOGGER.error(ctx.format("Graph vanilla operation threw for {}"),
                        vanillaNode, exception);
                executed = false;
            }
            if (!executed || state == State.ABORTED) {
                // Vanilla execution is synchronous and has no external machine side
                // effect. A failed logical start can therefore refund exact inputs.
                graphAdmissions.refundCommittedMaterial(admission);
                if (nodeLedger.isCommitted()) refundCommitted(nodeLedger, online);
                operation.close();
                nodeLedger.close();
                if (graphScheduler.state(vanillaNode) == DagScheduler.NodeState.RUNNING) {
                    graphScheduler.fail(vanillaNode);
                }
                return;
            }
            boolean outputsComplete = publishDeclaredNodeOutputs(vanillaNode, operationInventory);
            OperationExecutionKernel.CompletionResult completion = operation.complete(
                    () -> outputsComplete, () -> {
                        graphAdmissions.settleMaterial(admission);
                        nodeLedger.settleAllCommitted();
                    });
            operation.close();
            nodeLedger.close();
            if (completion == OperationExecutionKernel.CompletionResult.OUTPUT_SHORTAGE) {
                String detail = "output shortage after synchronous crafting";
                graphFailureDetails.put(vanillaNode, detail);
                RSIntegrationMod.LOGGER.warn(ctx.format("Graph node {} completion failed: {}"),
                        vanillaNode, detail);
                graphScheduler.fail(vanillaNode);
                return;
            }
            snapshotCommittedVirtual();
            graphScheduler.succeed(vanillaNode);
            graphProgressWatchdog.markProgress();
            currentStepIdx = idx + 1;
        }
    }

    private void logGraphNodeMappings(CraftPlanGraph plan) {
        if (!RSIntegrationMod.LOGGER.isDebugEnabled()) return;
        for (CraftNode node : plan.nodes()) {
            RSIntegrationMod.LOGGER.debug(ctx.format(
                            "[RSI-GraphNode] node={} recipe={} modType={} recipeType={} "
                                    + "executions={} outputs={}"),
                    node.id().value(), node.recipeId(), node.modTypeId(),
                    node.recipeTypeId(), node.executions(),
                    describeOutputDeclarations(node.outputs()));
        }
    }

    static boolean claimVanillaBudgetOrReleaseAdmission(
            VanillaCraftingTickBudget.ChainAllowance allowance,
            NodeAdmissionCoordinator admissions,
            NodeAdmissionCoordinator.Admission admission,
            int executions) {
        if (allowance.tryClaimExact(executions)) return true;
        admissions.releaseBeforeDispatch(admission);
        return false;
    }

    @Nullable
    private NodeId findReadyVanillaNode() {
        return graphScheduler.claimFirstReady(this::isVanillaGraphNode);
    }

    private boolean isVanillaGraphNode(NodeId nodeId) {
        CraftingResolver.ResolutionStep step = graphStep(nodeId);
        return step.modType() == ModType.GENERIC
                && !step.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP);
    }

    private boolean isConcurrentGraphNode(NodeId nodeId) {
        CraftingResolver.ResolutionStep step = graphStep(nodeId);
        return step.modType() != ModType.GENERIC
                && !step.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP);
    }

    /**
     * Decide, WITHOUT starting a craft, whether a node must run exclusively.
     * A node is exclusive unless its delegate exposes a complete capability contract.
     * This is the pre-dispatch enforcement behind the capability gate: the
     * probe only instantiates a throwaway delegate (no {@code validateAndInit},
     * no material extraction), so it has no side effects. Synchronous nodes
     * (vanilla GENERIC, Earth Heart) settle outside the executor and never
     * reach this oracle.
     */
    private boolean isNodeExclusive(NodeId nodeId) {
        CraftingResolver.ResolutionStep step = graphStep(nodeId);
        if (step.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP)) return true;
        IBatchDelegate probe = createStepDelegate(step);
        GraphConcurrencyPolicy.Decision decision = concurrencyDecision(step, probe);
        if (decision.exclusive()) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-GraphConcurrency] nodeId={} recipe={} modType={} delegate={} decision=exclusive reason={}",
                    nodeId, step.recipeId(), step.modType().id(),
                    probe == null ? "none" : probe.getClass().getSimpleName(),
                    decision.reason());
        } else {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-GraphConcurrency] nodeId={} recipe={} modType={} delegate={} decision=parallel capability={}",
                    nodeId, step.recipeId(), step.modType().id(),
                    probe.getClass().getSimpleName(), decision.capabilities());
        }
        return decision.exclusive();
    }

    private GraphConcurrencyPolicy.Decision concurrencyDecision(
            CraftingResolver.ResolutionStep step, IBatchDelegate delegate) {
        Recipe<?> recipe = server.overworld().getRecipeManager()
                .byKey(step.recipeId()).orElse(null);
        String recipeClass = recipe == null ? "" : recipe.getClass().getName();
        var recipeCapability = GraphConcurrencyEligibility.capabilities(
                new GraphConcurrencyEligibility.Context(
                        step.modType().id(), recipeClass, step.inferMode()));
        return GraphConcurrencyPolicy.decide(
                step.modType().id(), delegate, recipeCapability);
    }

    private enum PreparationState { READY, RETRY, FATAL }

    private record PreparationResult(PreparationState state,
                                     @Nullable PreparedGraphNode prepared,
                                     String detail,
                                     @Nullable Component userMessage) {
        static PreparationResult ready(PreparedGraphNode prepared) {
            return new PreparationResult(PreparationState.READY, prepared, "", null);
        }

        static PreparationResult retry(String detail) {
            return new PreparationResult(PreparationState.RETRY, null, detail, null);
        }

        static PreparationResult fatal(String detail) {
            return fatal(detail, null);
        }

        static PreparationResult fatal(String detail, @Nullable Component userMessage) {
            return new PreparationResult(PreparationState.FATAL, null, detail, userMessage);
        }
    }

    private enum DispatchState { STARTED, RETRY, FATAL }

    private record GraphDispatchResult(DispatchState state,
                                       @Nullable ConcurrentNodeExecutor.Worker worker,
                                       String detail) {
        static GraphDispatchResult started(ConcurrentNodeExecutor.Worker worker) {
            return new GraphDispatchResult(DispatchState.STARTED,
                    Objects.requireNonNull(worker, "worker"), "");
        }

        static GraphDispatchResult retry(String detail) {
            return new GraphDispatchResult(DispatchState.RETRY, null, detail == null ? "" : detail);
        }

        static GraphDispatchResult fatal(String detail) {
            return new GraphDispatchResult(DispatchState.FATAL, null, detail == null ? "" : detail);
        }
    }

    private record PreparedGraphNode(CraftingResolver.ResolutionStep step,
                                     IBatchDelegate delegate,
                                     List<BoundMachine> machines,
                                     int operationCost,
                                     boolean parallelGroup) {
        PreparedGraphNode {
            machines = List.copyOf(machines);
            if (machines.isEmpty()) throw new IllegalArgumentException("graph node needs a machine");
            if (operationCost <= 0 || operationCost > machines.size()) {
                throw new IllegalArgumentException("invalid operation cost");
            }
            if (!parallelGroup && operationCost != 1) {
                throw new IllegalArgumentException("single graph node must cost one operation");
            }
        }

        BoundMachine machine() { return machines.get(0); }
    }

    private ConcurrentNodeExecutor.StartResult startAdmittedGraphNode(
            NodeId nodeId, ServerPlayer online) {
        if (graphAdmissions == null) return ConcurrentNodeExecutor.StartResult.failed();
        int currentTick = server.getTickCount();
        Integer retryUntil = graphRetryUntilTick.get(nodeId);
        if (retryUntil != null && currentTick < retryUntil) {
            return ConcurrentNodeExecutor.StartResult.retry();
        }
        if (graph != null && !CraftPlanningRevision.isCurrent(graph.planningRevision())) {
            graphFailureDetails.put(nodeId, "Crafting plan is stale after recipe or matcher reload");
            return ConcurrentNodeExecutor.StartResult.failed();
        }
        CraftingResolver.ResolutionStep step = graphStep(nodeId);

        if (step.modType() == ModType.GENERIC) {
            graphFailureDetails.put(nodeId,
                    "synchronous generic node reached concurrent graph dispatcher");
            return ConcurrentNodeExecutor.StartResult.failed();
        }

        // Earth Heart is synchronous and owns no machine/capture resources.
        if (step.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP)) {
            NodeAdmissionCoordinator.Candidate candidate = new NodeAdmissionCoordinator.Candidate(
                    nodeId, graphRequests.getOrDefault(nodeId, List.of()));
            NodeAdmissionCoordinator.Admission admission = graphAdmissions.tryAdmitClaimed(candidate);
            if (admission == null) {
                return graphRetry(nodeId, "material admission unavailable",
                        candidate.materialRequests());
            }
            if (!startEarthHeartTaint(online, step.executions())) {
                graphAdmissions.releaseMaterial(admission);
                return ConcurrentNodeExecutor.StartResult.failed();
            }
            clearGraphRetry(nodeId);
            graphAdmissions.commit(admission);
            graphAdmissions.settleMaterial(admission);
            if (!publishDeclaredNodeOutputs(nodeId, virtualInventory)) {
                return ConcurrentNodeExecutor.StartResult.failed();
            }
            return ConcurrentNodeExecutor.StartResult.completed();
        }

        if (craftOperationBudget.availableCapacity() <= 0
                || globalOperationBudget.availableCapacity() <= 0) {
            return graphRetry(nodeId, "operation budget temporarily unavailable",
                    graphRequests.getOrDefault(nodeId, List.of()));
        }
        PreparationResult preparation = prepareGraphNode(nodeId, step, online);
        if (preparation.state() == PreparationState.RETRY) {
            return graphRetry(nodeId, preparation.detail(),
                    graphRequests.getOrDefault(nodeId, List.of()));
        }
        if (preparation.state() == PreparationState.FATAL || preparation.prepared() == null) {
            graphFailureDetails.put(nodeId, preparation.detail());
            RSIntegrationMod.LOGGER.warn(ctx.format("Graph node {} preparation failed: {}"),
                    nodeId, preparation.detail());
            if (preparation.userMessage() != null) {
                online.sendSystemMessage(preparation.userMessage());
            }
            return ConcurrentNodeExecutor.StartResult.failed();
        }
        PreparedGraphNode prepared = preparation.prepared();
        NodeAdmissionCoordinator.Candidate candidate = new NodeAdmissionCoordinator.Candidate(
                nodeId, graphRequests.getOrDefault(nodeId, List.of()));
        NodeAdmissionCoordinator.Admission admission = graphAdmissions.tryAdmitClaimed(candidate);
        if (admission == null) {
            releasePreparationQuietly(prepared.delegate());
            return graphRetry(nodeId, "material admission unavailable", candidate.materialRequests());
        }

        GraphDispatchResult dispatch = dispatchPreparedGraphNode(
                nodeId, prepared, online, admission);
        if (dispatch.state() == DispatchState.RETRY) {
            graphAdmissions.releaseMaterial(admission);
            return graphRetry(nodeId, dispatch.detail(), candidate.materialRequests());
        }
        if (dispatch.state() == DispatchState.FATAL || dispatch.worker() == null) {
            graphFailureDetails.put(nodeId, dispatch.detail());
            RSIntegrationMod.LOGGER.warn(ctx.format("Graph node {} dispatch failed: {}"),
                    nodeId, dispatch.detail());
            graphAdmissions.releaseMaterial(admission);
            return ConcurrentNodeExecutor.StartResult.failed();
        }
        clearGraphRetry(nodeId);
        return ConcurrentNodeExecutor.StartResult.started(dispatch.worker());
    }

    private ConcurrentNodeExecutor.StartResult graphRetry(
            NodeId nodeId, String detail, List<MaterialBroker.Request> requests) {
        int attempts = Math.min(graphRetryAttempts.merge(nodeId, 1, Integer::sum), 6);
        int delay = Math.min(GRAPH_RETRY_MAX_DELAY_TICKS, 1 << attempts);
        graphRetryUntilTick.put(nodeId, server.getTickCount() + delay);
        logGraphRetry(nodeId, detail, requests);
        return ConcurrentNodeExecutor.StartResult.retry();
    }

    private void clearGraphRetry(NodeId nodeId) {
        graphRetryUntilTick.remove(nodeId);
        graphRetryAttempts.remove(nodeId);
    }

    private void logGraphRetry(NodeId nodeId, String detail,
                               List<MaterialBroker.Request> requests) {
        String key = craftId + ":" + detail;
        int count = graphRetryCounts.merge(key, 1, Integer::sum);
        if (GRAPH_RETRY_LOGS.allow(key)) {
            RSIntegrationMod.LOGGER.debug(ctx.format(
                    "[RSI-GraphRetry] node={} detail={} attemptsSinceLog={} requests={}"),
                    nodeId, detail, count, requests);
            graphRetryCounts.put(key, 0);
        }
    }

    static boolean validatePreparedDelegate(
            IBatchDelegate delegate, ServerPlayer player, ResourceLocation recipeId,
            @Nullable ResourceLocation dimension, BlockPos position) {
        boolean valid = false;
        try {
            valid = PreparationMessageScope.validate(
                    delegate, player, recipeId, dimension, position);
            return valid;
        } finally {
            if (!valid) releasePreparationQuietly(delegate);
        }
    }

    private static void releasePreparationQuietly(IBatchDelegate delegate) {
        try {
            delegate.releasePreparationResources();
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Delegate] Preparation resource cleanup failed for {}",
                    delegate.getClass().getSimpleName(), exception);
        }
    }

    private PreparationResult prepareGraphNode(NodeId nodeId,
                                               CraftingResolver.ResolutionStep step,
                                               ServerPlayer online) {
        List<BoundMachine> machines = deduplicateMachines(AltarBindingRegistry.getBoundMachinesForRecipe(
                online, step.modType(), step.recipeId()));
        machines.sort((a, b) -> {
            ResourceLocation playerDim = online.level().dimension().location();
            boolean aSame = a.dim().equals(playerDim);
            boolean bSame = b.dim().equals(playerDim);
            return aSame == bSame ? 0 : aSame ? -1 : 1;
        });
        machines = applyMachineSelection(machines, step.recipeId());
        IBatchDelegate delegate = createStepDelegate(step);
        if (delegate == null) {
            return PreparationResult.fatal("No delegate for mod type " + step.modType().id());
        }
        // Only the exact generic delegate is logical/positionless. Physical adapters may
        // reuse its material transaction logic while still requiring a bound machine.
        if (delegate.getClass() == GenericBatchDelegate.class) {
            if (!validatePreparedDelegate(
                    delegate, online, step.recipeId(), null, BlockPos.ZERO)) {
                return PreparationResult.fatal("Virtual recipe validation failed for " + step.recipeId());
            }
            try {
                if (delegate instanceof AbstractBatchDelegate abd) {
                    abd.setMachineServer(server);
                    ItemStack nodeTarget = graphNodeTargetOutput(graphNodes.get(nodeId));
                    if (!nodeTarget.isEmpty()) {
                        abd.setTargetOutput(nodeTarget);
                    } else if (targetOutput != null && isGraphTerminalNode(nodeId)) {
                        abd.setTargetOutput(targetOutput);
                    }
                }
            } catch (RuntimeException exception) {
                releasePreparationQuietly(delegate);
                return PreparationResult.fatal(
                        "Virtual delegate configuration failed for " + step.recipeId());
            }
            BoundMachine virtual = new BoundMachine(online.level().dimension().location(),
                    BlockPos.ZERO, step.modType(), "virtual");
            return PreparationResult.ready(new PreparedGraphNode(step, delegate,
                    List.of(virtual), 1, false));
        }
        if (machines.isEmpty()) {
            return PreparationResult.fatal("No bound machine matches " + step.recipeId());
        }
        List<BoundMachine> available = LoadBalancer.filterAvailable(machines, server, delegate);
        available = filterUnleasedMachines(available, machineLeases, step.modType().id());
        if (available.isEmpty()) {
            return PreparationResult.retry("all bound machines are busy, unloaded, or unavailable");
        }
        List<BoundMachine> eligible = new ArrayList<>();
        releasePreparationQuietly(delegate);
        delegate = null;
        boolean validationThrew = false;
        boolean retryableRejection = false;
        boolean protectionRejection = false;
        String fatalDetail = "";
        Component fatalUserMessage = null;
        for (BoundMachine machine : available) {
            IBatchDelegate candidate = null;
            boolean retained = false;
            try {
                ResourceKey<Level> dimKey = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, machine.dim());
                ServerLevel machineLevel = server.getLevel(dimKey);
                if (machineLevel == null
                        || !ProtectionChecker.canInteract(online, machineLevel, machine.pos())) {
                    protectionRejection = true;
                    continue;
                }
                candidate = createStepDelegate(step);
                if (candidate == null) {
                    fatalDetail = "delegate factory returned null for " + step.modType().id();
                    continue;
                }
                if (candidate instanceof AbstractBatchDelegate abd) {
                    // Preparation may inspect storage-backed fuel or catalyst
                    // availability. Reuse the endpoint selected for this chain.
                    abd.setStorageEndpoint(storageEndpoint);
                }
                IBatchDelegate.PreparationResult result = PreparationMessageScope.prepare(
                        candidate, online, step.recipeId(), machine.dim(), machine.pos());
                if (result.state() == IBatchDelegate.PreparationState.READY) {
                    if (candidate instanceof AbstractBatchDelegate abd) {
                        abd.setMachineDim(machine.dim());
                        abd.setMachineServer(server);
                        ItemStack nodeTarget = graphNodeTargetOutput(graphNodes.get(nodeId));
                        if (!nodeTarget.isEmpty()) {
                            abd.setTargetOutput(nodeTarget);
                        } else if (targetOutput != null && isGraphTerminalNode(nodeId)) {
                            abd.setTargetOutput(targetOutput);
                        }
                    }
                    if (eligible.isEmpty()) {
                        delegate = candidate;
                        retained = true;
                    }
                    eligible.add(machine);
                } else if (result.state() == IBatchDelegate.PreparationState.RETRY) {
                    retryableRejection = true;
                } else if (fatalDetail.isEmpty()) {
                    fatalDetail = result.detail();
                    fatalUserMessage = result.userMessage();
                }
            } catch (RuntimeException exception) {
                validationThrew = true;
                RSIntegrationMod.LOGGER.debug(ctx.format("Graph node probe failed for {}"),
                        machine.pos(), exception);
            } finally {
                if (candidate != null && !retained) releasePreparationQuietly(candidate);
            }
        }
        if (eligible.isEmpty()) {
            if (protectionRejection && !retryableRejection && !validationThrew) {
                return PreparationResult.fatal("all loaded machines denied by protection provider",
                        Component.translatable("rsi.error.protection_denied"));
            }
            if (!retryableRejection && !validationThrew && !fatalDetail.isEmpty()) {
                return PreparationResult.fatal(fatalDetail, fatalUserMessage);
            }
            return PreparationResult.retry(validationThrew
                    ? "delegate validation temporarily failed on every available machine"
                    : "no available machine currently accepts the recipe");
        }
        int desiredOperations = Math.max(1, step.executions());
        int availableOperations = Math.min(craftOperationBudget.availableCapacity(),
                globalOperationBudget.availableCapacity());
        boolean operationGroup = shouldUseGraphOperationGroup(desiredOperations, availableOperations);
        boolean concurrencySafe = !concurrencyDecision(step, delegate).exclusive();
        boolean workerReusable = delegate.getMaterialReservationScopes().contains(
                IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE);
        int operationCost = graphOperationWorkerCount(desiredOperations, availableOperations,
                eligible.size(), concurrencySafe, workerReusable);
        if (operationGroup && workerReusable) {
            operationCost = Math.min(operationCost, Math.max(1,
                    reusableWorkerCapacity(delegate.getGraphSpecs(),
                            delegate.getMaterialReservationScopes(), online, storageEndpoint)));
        }
        return PreparationResult.ready(
                new PreparedGraphNode(step, delegate, eligible, operationCost, operationGroup));
    }

    /** Exact per-execution output used to configure runtime-derived graph recipes. */
    static ItemStack graphNodeTargetOutput(@Nullable CraftNode node) {
        if (node == null) return ItemStack.EMPTY;
        return node.outputs().stream()
                .filter(output -> output.kind() == OutputKind.PRIMARY
                        || output.kind() == OutputKind.DYNAMIC)
                .findFirst()
                .map(output -> output.material().toStack(Math.max(1,
                        output.quantity() / Math.max(1, node.executions()))))
                .orElse(ItemStack.EMPTY);
    }

    static boolean shouldUseGraphOperationGroup(int executions, int availableOperations) {
        return executions >= 2 && availableOperations >= 1;
    }

    static int graphOperationWorkerCount(int executions, int availableOperations,
                                         int eligibleMachines, boolean concurrencySafe,
                                         boolean workerReusable) {
        if (!shouldUseGraphOperationGroup(executions, availableOperations)
                || !concurrencySafe) return 1;
        return Math.max(1, Math.min(Math.min(executions, availableOperations), eligibleMachines));
    }

    /**
     * Reusable materials are required once per physical worker.  The graph
     * planner intentionally records only one catalyst demand, so use the live
     * RS/player snapshot to avoid selecting more workers than can be equipped.
     * A missing snapshot is treated conservatively as one worker; the broker
     * admission still remains authoritative for planned producer outputs.
     */
    static int reusableWorkerCapacity(List<IngredientSpec> specs,
                                      List<IBatchDelegate.MaterialReservationScope> scopes,
                                      ServerPlayer online, @Nullable INetwork network) {
        if (specs == null || specs.isEmpty() || online == null) return 1;
        Map<CraftingResolver.StackKey, Integer> available = network == null
                ? MaterialSources.listAllAvailable(online, (INetwork) null)
                : MaterialSources.listAllAvailable(online, network);
        return reusableWorkerCapacity(specs, scopes, available);
    }

    static int reusableWorkerCapacity(List<IngredientSpec> specs,
                                      List<IBatchDelegate.MaterialReservationScope> scopes,
                                      ServerPlayer online, @Nullable CraftStorageEndpoint endpoint) {
        if (specs == null || specs.isEmpty() || online == null) return 1;
        return reusableWorkerCapacity(specs, scopes,
                MaterialSources.listAllAvailable(online, endpoint));
    }

    static int reusableWorkerCapacity(List<IngredientSpec> specs,
                                      List<IBatchDelegate.MaterialReservationScope> scopes,
                                      Map<CraftingResolver.StackKey, Integer> available) {
        if (specs == null || specs.isEmpty()) return 1;
        int capacity = Integer.MAX_VALUE;
        boolean found = false;
        for (int i = 0; i < specs.size() && i < scopes.size(); i++) {
            if (scopes.get(i) != IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE) continue;
            IngredientSpec spec = specs.get(i);
            if (spec.isEmpty()) continue;
            found = true;
            int matching = 0;
            for (Map.Entry<CraftingResolver.StackKey, Integer> entry : available.entrySet()) {
                if (IngredientMatcher.test(spec.ingredient(), entry.getKey())) {
                    matching += entry.getValue();
                }
            }
            capacity = Math.min(capacity, matching / Math.max(1, spec.count()));
        }
        return found ? Math.max(1, capacity) : 1;
    }

    private GraphDispatchResult dispatchPreparedGraphNode(
            NodeId nodeId, PreparedGraphNode prepared, ServerPlayer online,
            NodeAdmissionCoordinator.Admission admission) {
        ExtractionLedger nodeLedger = new ExtractionLedger();
        nodeLedger.setLogContext(ctx);
        if (storageEndpoint != null) nodeLedger.setStorageEndpoint(storageEndpoint);
        IBatchDelegate delegate = prepared.delegate();
        OperationExecutionKernel.Session operationSession = null;
        boolean ownershipTransferred = false;
        boolean terminalCleanupInvoked = false;
        try {
            delegate.configureMaterialReservation(nodeLedger, online);
            delegate.prepareGraphBatch(Math.max(1, prepared.step().executions()));
            delegate.prepareOperationCount(Math.max(1, prepared.step().executions()));
            if (prepared.parallelGroup()) {
                List<BoundMachine> workers = new ArrayList<>(
                        prepared.machines().subList(0, prepared.operationCost()));
                var groupCapability = concurrencyDecision(prepared.step(), delegate).capabilities();
                ParallelCraftGroup group = new ParallelCraftGroup(workers,
                        prepared.step().modType(), prepared.step().recipeId(), online,
                        prepared.step().executions(), prepared.step().inferMode(), groupCapability);
                if (validatePreparedDelegate(
                        group, online, prepared.step().recipeId(), null, BlockPos.ZERO)) {
                    releasePreparationQuietly(delegate);
                    delegate = group;
                    group.setMachineServer(server);
                    ItemStack nodeTarget = graphNodeTargetOutput(graphNodes.get(nodeId));
                    if (!nodeTarget.isEmpty()) {
                        group.setTargetOutput(nodeTarget);
                    } else if (targetOutput != null && isGraphTerminalNode(nodeId)) {
                        group.setTargetOutput(targetOutput);
                    }
                } else {
                    return GraphDispatchResult.retry(
                            "operation group could not prepare a worker for serial dispatch");
                }
            }
            GraphNodeMaterials reserved;
            if (delegate instanceof CrockPotBatchDelegate crockPot
                    && crockPot.usesPlannedCategoryMaterials()) {
                reserved = reservePlannedCheckoutMaterials(
                        online, nodeLedger, admission.materialToken());
            } else {
                List<IngredientSpec> graphSpecs = delegate.getGraphSpecs();
                if (graphSpecs == null || graphSpecs.isEmpty()) {
                    if (shouldUsePrivateLedgerGraphDispatch(delegate, graphSpecs)) {
                        GraphDispatchResult result = dispatchPrivateLedgerGraphNode(
                                nodeId, prepared, delegate, online, admission, nodeLedger);
                        ownershipTransferred = result.state() == DispatchState.STARTED;
                        return result;
                    }
                    return GraphDispatchResult.fatal("delegate did not expose graph materials");
                }
                reserved = reserveGraphNodeMaterials(
                        delegate, graphSpecs, prepared.step().executions(), online,
                        nodeLedger, admission.materialToken());
            }
            if (reserved == null) return GraphDispatchResult.retry("exact graph materials are temporarily unavailable");
            List<ItemStack> materials = reserved.materials();

            List<IngredientSpec> supplementalSpecs = delegate.getSupplementalSpecs();
            if (supplementalSpecs != null && !supplementalSpecs.isEmpty()) {
                List<ItemStack> supplementalMaterials = reserveSupplementalMaterials(
                        supplementalSpecs, online, nodeLedger);
                if (supplementalMaterials == null) {
                    return GraphDispatchResult.retry("supplemental materials unavailable");
                }
                materials = delegate.mergeSupplementalMaterials(materials, supplementalMaterials);
            }
            if (!delegate.validateExecutionContext(online)) {
                return GraphDispatchResult.fatal(
                        "execution context changed before material commit");
            }
            if (delegate instanceof ParallelCraftGroup group) {
                group.setReservationTokens(reserved.operationTokens());
                group.setReusableReservationTokens(reserved.reusableTokens());
                group.setVirtualDebits(reserved.virtualDebits());
                group.setProducerDebits(reserved.producerDebits());
                group.setOperationKernel(operationKernel, craftId, nodeId, craftOperationBudget);
            } else {
                GraphConcurrencyPolicy.Decision concurrency = concurrencyDecision(
                        prepared.step(), delegate);
                ItemStack expected = delegate.getExpectedOutput();
                AABB region = delegate.getOutputCaptureRegion();
                boolean ownsWorldCapture = concurrency.capabilities() != null
                        && concurrency.capabilities().outputOwnership()
                        == BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE;
                OperationResourceCoordinator.CaptureRequest capture = expected != null
                        && !expected.isEmpty() && region != null
                        ? new OperationResourceCoordinator.CaptureRequest(
                        prepared.machine().dim(), region, expected,
                        "malum".equals(prepared.step().modType().id())
                                || delegate.allowsOverlappingOutputCaptureOrigins()) : null;
                if (expected != null && !expected.isEmpty() && region == null) {
                    return GraphDispatchResult.fatal("delegate expects a world output without a capture region");
                }
                if (!concurrency.exclusive() && expected != null && !expected.isEmpty()
                        && !ownsWorldCapture) {
                    return GraphDispatchResult.fatal("world capture was not declared by delegate capability");
                }
                List<MachineLeaseRegistry.MachineKey> machineScope = new ArrayList<>();
                BlockPos operationMachinePos = delegate.getOperationMachinePos(prepared.machine().pos());
                machineScope.add(new MachineLeaseRegistry.MachineKey(
                        prepared.machine().dim(), operationMachinePos, prepared.step().modType().id()));
                if (concurrency.capabilities() != null) {
                    for (BlockPos offset : concurrency.capabilities().supportOffsets()) {
                        machineScope.add(new MachineLeaseRegistry.MachineKey(
                                prepared.machine().dim(), operationMachinePos.offset(offset),
                                prepared.step().modType().id() + ":support"));
                    }
                }
                operationSession = operationKernel.tryPrepare(craftId, nodeId, 0,
                        craftOperationBudget, machineScope, capture);
                if (operationSession == null) {
                    return GraphDispatchResult.retry("operation budget, machine, or capture is temporarily unavailable");
                }
            }
            boolean committed = operationSession != null
                    ? operationSession.commit(() -> commitLedger(nodeLedger, online))
                    : commitLedger(nodeLedger, online);
            if (!committed) {
                if (operationSession != null) operationSession.close();
                return GraphDispatchResult.retry("node ledger commit did not complete");
            }
            if (delegate instanceof AbstractBatchDelegate abd) abd.useSharedLedger(nodeLedger);

            CraftNodeRuntime runtime = new CraftNodeRuntime(nodeId,
                    prepared.step().recipeId().toString(), delegate, nodeLedger,
                    admission, operationSession);
            runtime.setReusableReservationTokens(reserved.reusableTokens());
            CraftNode graphNode = graphNodes.get(nodeId);
            if (graphNode != null) runtime.attachOutputs(new NodeOutputAccumulator(graphNode.outputs()));
            runtime.setChainContext(virtualInventory, online);

            graphAdmissions.commit(admission);
            runtime.markDispatched();
            nodeRuntimes.put(nodeId, runtime);
            ownershipTransferred = true;
            IBatchDelegate startDelegate = delegate;
            List<ItemStack> startMaterials = materials;
            RSIntegrationMod.LOGGER.debug(ctx.format(
                    "Graph node dispatching: node={} recipe={} delegate={} machine={} materials={}"),
                    nodeId, prepared.step().recipeId(), delegate.getClass().getSimpleName(),
                    prepared.machine().pos(), startMaterials.size());
            boolean accepted = operationSession != null
                    ? operationSession.tryStart(
                    () -> startDelegate.tryStartWithMaterials(online, startMaterials, nodeLedger))
                    : startDelegate.tryStartWithMaterials(online, startMaterials, nodeLedger);
            if (!accepted) {
                RSIntegrationMod.LOGGER.warn(ctx.format(
                        "Graph node delegate rejected start: node={} recipe={} delegate={} machine={}"),
                        nodeId, prepared.step().recipeId(), delegate.getClass().getSimpleName(),
                        prepared.machine().pos());
                runtime.markStartFailed("delegate rejected graph dispatch after start attempt: delegate="
                        + delegate.getClass().getSimpleName()
                        + " recipe=" + prepared.step().recipeId());
            } else {
                attachDeferredGraphCapture(runtime, startDelegate, prepared.machine(), nodeId);
            }
            return GraphDispatchResult.started(runtime);
        } catch (RuntimeException exception) {
            String message = delegate.getClass().getSimpleName() + " start threw "
                    + exception.getClass().getSimpleName()
                    + (exception.getMessage() != null ? ": " + exception.getMessage() : "");
            RSIntegrationMod.LOGGER.error(ctx.format("Graph node {} dispatch threw in {}"),
                    nodeId, delegate.getClass().getSimpleName(), exception);
            CraftNodeRuntime runtime = nodeRuntimes.get(nodeId);
            if (runtime != null) {
                ownershipTransferred = true;
                runtime.markStartFailed(message);
                return GraphDispatchResult.started(runtime);
            }
            if (operationSession != null) operationSession.close();
            if (nodeLedger.isCommitted()) refundCommitted(nodeLedger, online);
            try {
                delegate.onBatchFailed(online, "graph dispatch failed before start");
                terminalCleanupInvoked = true;
            } catch (RuntimeException ignored) {
                terminalCleanupInvoked = true;
            }
            return GraphDispatchResult.fatal(message);
        } finally {
            if (!ownershipTransferred && !terminalCleanupInvoked) {
                releasePreparationQuietly(delegate);
            }
        }
    }

    static boolean shouldUsePrivateLedgerGraphDispatch(
            IBatchDelegate delegate, List<IngredientSpec> graphSpecs) {
        return delegate != null
                && (graphSpecs == null || graphSpecs.isEmpty())
                && delegate.requiresPrivateLedgerGraphDispatch();
    }

    /**
     * Attach capture for delegates whose concrete world output is learned only
     * while starting. The operation scope is intentionally acquired before
     * start, so its original capture request can be empty for these machines.
     */
    private void attachDeferredGraphCapture(CraftNodeRuntime runtime,
                                            IBatchDelegate delegate,
                                            BoundMachine machine,
                                            NodeId nodeId) {
        if (runtime == null || delegate == null || machine == null) return;
        ItemStack expected = delegate.getExpectedOutput();
        AABB region = delegate.getOutputCaptureRegion();
        if (expected == null || expected.isEmpty() || region == null) return;
        if (runtime.hasCaptureScope()) return;

        CaptureLeaseRegistry.Lease lease = captureLeases.tryAcquire(
                machine.dim(), region, MaterialKey.of(expected),
                new CaptureLeaseRegistry.Owner(craftId, nodeId, 0),
                delegate.allowsOverlappingOutputCaptureOrigins());
        if (lease == null) {
            RSIntegrationMod.LOGGER.warn(ctx.format(
                    "Deferred graph output capture unavailable for node={} recipe={} machine={}"),
                    nodeId, delegate.getClass().getSimpleName(), machine.pos());
            return;
        }
        ResourceKey<Level> dimension = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, machine.dim());
        CraftOutputInterceptor.CaptureHandle handle = CraftOutputInterceptor.arm(
                dimension, region, expected,
                delegate.allowsOverlappingOutputCaptureOrigins());
        if (handle == null) {
            captureLeases.release(lease);
            RSIntegrationMod.LOGGER.warn(ctx.format(
                    "Deferred graph interceptor unavailable for node={} machine={}"),
                    nodeId, machine.pos());
            return;
        }
        runtime.attachCapture(CaptureSession.arm(captureLeases, lease, handle));
    }

    private GraphDispatchResult dispatchPrivateLedgerGraphNode(
            NodeId nodeId, PreparedGraphNode prepared, IBatchDelegate delegate,
            ServerPlayer online, NodeAdmissionCoordinator.Admission admission,
            ExtractionLedger nodeLedger) {
        List<MachineLeaseRegistry.MachineKey> machineScope = List.of(
                new MachineLeaseRegistry.MachineKey(prepared.machine().dim(),
                        prepared.machine().pos(), prepared.step().modType().id()));
        OperationExecutionKernel.Session operationSession = operationKernel.tryPrepare(
                craftId, nodeId, 0, craftOperationBudget, machineScope, null);
        if (operationSession == null) {
            return GraphDispatchResult.retry("operation budget or machine is temporarily unavailable");
        }
        if (!operationSession.commit(() -> nodeLedger.commit(network, online))) {
            operationSession.close();
            return GraphDispatchResult.retry("node ledger commit did not complete");
        }

        CraftNodeRuntime runtime = new CraftNodeRuntime(nodeId,
                prepared.step().recipeId().toString(), delegate, nodeLedger,
                admission, operationSession);
        CraftNode graphNode = graphNodes.get(nodeId);
        if (graphNode != null) runtime.attachOutputs(new NodeOutputAccumulator(graphNode.outputs()));
        runtime.setChainContext(virtualInventory, online);
        graphAdmissions.commit(admission);
        runtime.markDispatched();
        nodeRuntimes.put(nodeId, runtime);
        materializePrivateLedgerGraphInputs(admission, online);

        boolean accepted = operationSession.tryStart(() -> delegate.tryStartSingleCraft(online));
        if (!accepted) runtime.markStartFailed("delegate rejected private-ledger graph dispatch");
        return GraphDispatchResult.started(runtime);
    }

    /**
     * Private-ledger delegates read their inputs from RS/player storage rather
     * than from the graph checkout. Move producer outputs into RS before the
     * delegate starts. Once externalized, settle the broker claim immediately:
     * the delegate's private ledger now owns physical recovery, and returning
     * the producer claim to the graph on failure would deliver it twice.
     */
    private void materializePrivateLedgerGraphInputs(
            NodeAdmissionCoordinator.Admission admission, ServerPlayer online) {
        if (graphMaterials == null) return;
        MaterialBroker.Checkout checkout = graphMaterials.checkout(admission.materialToken());
        for (ItemStack producer : checkout.producerStacks()) {
            if (producer.isEmpty()) continue;
            ItemStack leftover = producer.copy();
            try {
                leftover = insertIntoStorage(online, producer);
            } catch (RuntimeException exception) {
                RSIntegrationMod.LOGGER.warn(ctx.format(
                        "Producer material transfer to RS failed for private-ledger node {}"),
                        admission.nodeId(), exception);
            }
            if (!leftover.isEmpty()) {
                PlayerUtils.safeGiveToPlayer(online, leftover, network);
            }
        }
        // Private-ledger delegates resolve their inputs from MaterialSources.
        // The producer stacks were just inserted into RS above, so invalidate
        // the per-player snapshot before the delegate starts in this same tick.
        // Without this, a freshly produced intermediate (for example a Goety
        // ritual input) can be reported as missing until the player reconnects.
        if (online != null) {
            MaterialSources.invalidateFor(online);
        }
        graphAdmissions.settleMaterialOnce(admission);
    }

    private void publishIncrementalGraphOutputs(NodeId nodeId, ConcurrentNodeExecutor.Worker worker) {
        if (!(worker instanceof CraftNodeRuntime runtime) || graphMaterials == null) return;
        List<NodeOutputAccumulator.Publication> publications = runtime.drainIncrementalOutputs();
        if (runtime.consumeProgressSignal() || !publications.isEmpty()) {
            graphProgressWatchdog.markProgress();
        }
        for (NodeOutputAccumulator.Publication publication : publications) {
            graphMaterials.publishActual(new MaterialSource.ProducerOutput(publication.port()),
                    publication.material(), publication.stack());
        }
        if (graphScheduler != null && !graphScheduler.isStopping()) {
            graphScheduler.refreshBlocked(candidate -> graphMaterials.canReserve(
                    graphRequests.getOrDefault(candidate, List.of())));
        }
    }

    private void recordGraphRuntimeFailure(NodeId nodeId, ConcurrentNodeExecutor.Worker worker) {
        String detail = worker instanceof CraftNodeRuntime runtime ? runtime.failureReason() : null;
        if (detail == null || detail.isBlank()) detail = "runtime worker failed without detail";
        graphFailureDetails.put(nodeId, detail);
        RSIntegrationMod.LOGGER.warn(ctx.format("Graph node {} runtime failed: {}"), nodeId, detail);
    }

    private ConcurrentNodeExecutor.CompletionStatus completeGraphNode(
            NodeId nodeId, ConcurrentNodeExecutor.Worker worker) {
        if (!(worker instanceof CraftNodeRuntime runtime) || graphAdmissions == null
                || graphMaterials == null) {
            return ConcurrentNodeExecutor.CompletionStatus.SUCCEEDED;
        }
        try {
            NodeAdmissionCoordinator.Admission admission = runtime.admission();
            ExtractionLedger nodeLedger = runtime.nodeLedger();
            publishIncrementalGraphOutputs(nodeId, runtime);
            OperationExecutionKernel.CompletionResult completion = runtime.completeOperation(
                    runtime::outputsComplete, () -> {
                        if (admission != null) graphAdmissions.settleMaterialOnce(admission);
                        if (nodeLedger != null && nodeLedger.isCommitted()) {
                            nodeLedger.settleAllCommitted();
                        }
                    });
            runtime.markResourcesClosed();
            if (completion == OperationExecutionKernel.CompletionResult.OUTPUT_SHORTAGE) {
                String detail = runtime.outputShortageDetail();
                runtime.markCompletionFailed(detail);
                graphFailureDetails.put(nodeId, detail);
                RSIntegrationMod.LOGGER.warn(ctx.format("Graph node {} completion failed: {}"),
                        nodeId, detail);
                for (ItemStack stack : runtime.drainOutputSurplus()) addToVirtualInventory(stack);
                nodeRuntimes.remove(nodeId);
                snapshotCommittedVirtual();
                return ConcurrentNodeExecutor.CompletionStatus.FAILED;
            }
            for (ItemStack stack : runtime.drainOutputSurplus()) addToVirtualInventory(stack);
            nodeRuntimes.remove(nodeId);
            snapshotCommittedVirtual();
            graphProgressWatchdog.markProgress();
            return ConcurrentNodeExecutor.CompletionStatus.SUCCEEDED;
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            String detail = "failed to settle graph node: "
                    + (message == null || message.isBlank()
                    ? exception.getClass().getSimpleName() : message);
            runtime.markCompletionFailed(detail);
            graphFailureDetails.put(nodeId, detail);
            RSIntegrationMod.LOGGER.error(ctx.format("Failed to settle graph node {}"), nodeId, exception);
            return ConcurrentNodeExecutor.CompletionStatus.FAILED;
        }
    }

    private void publishNodeOutputs(NodeId nodeId, List<ItemStack> actualOutputs) {
        if (!publishDeclaredNodeOutputs(nodeId, new ArrayList<>(copyStacks(actualOutputs)))) {
            throw new IllegalStateException("Graph node output did not satisfy its declarations: " + nodeId);
        }
    }

    /** Move exact runtime results into graph-owned lots after validating every declaration. */
    private boolean publishDeclaredNodeOutputs(NodeId nodeId, List<ItemStack> actualOutputs) {
        CraftNode node = graphNodes.get(nodeId);
        if (node == null || graphMaterials == null) return false;

        List<ItemStack> remaining = copyStacks(actualOutputs);
        Map<OutputDeclaration, List<ItemStack>> matched = new java.util.LinkedHashMap<>();
        List<String> checks = new ArrayList<>();
        for (OutputDeclaration output : node.outputs()) {
            List<ItemStack> fragments = removeMatchingFragments(
                    remaining, output.material(), output.quantity());
            int actualCount = fragments.stream().mapToInt(ItemStack::getCount).sum();
            checks.add(describeOutputCheck(output, actualCount));
            if (actualCount != output.quantity()) {
                RSIntegrationMod.LOGGER.warn(ctx.format(
                                "[RSI-GraphOutputMismatch] node={} recipe={} modType={} "
                                        + "recipeType={} executions={} failed={} checks={} "
                                        + "declarations={} actual={} unmatched={}"),
                        node.id().value(), node.recipeId(), node.modTypeId(),
                        node.recipeTypeId(), node.executions(),
                        describeOutputCheck(output, actualCount), checks,
                        describeOutputDeclarations(node.outputs()),
                        describeStacksForLogging(actualOutputs),
                        describeStacksForLogging(remaining));
                for (ItemStack stack : actualOutputs) addToVirtualInventory(stack);
                actualOutputs.clear();
                return false;
            }
            matched.put(output, fragments);
        }

        for (Map.Entry<OutputDeclaration, List<ItemStack>> entry : matched.entrySet()) {
            OutputDeclaration output = entry.getKey();
            MaterialSource source = new MaterialSource.ProducerOutput(output.id());
            for (ItemStack fragment : entry.getValue()) {
                graphMaterials.publishActual(source, output.material(), fragment);
            }
        }
        remaining.removeIf(ItemStack::isEmpty);
        for (ItemStack extra : remaining) addToVirtualInventory(extra);
        actualOutputs.clear();
        return true;
    }

    private static List<ItemStack> removeMatchingFragments(
            List<ItemStack> stacks, MaterialKey material, int limit) {
        int remaining = limit;
        List<ItemStack> removed = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (remaining <= 0) break;
            if (stack.isEmpty() || !MaterialMatcher.matchesOutputDeclaration(material, stack)) continue;
            int take = Math.min(remaining, stack.getCount());
            removed.add(stack.copyWithCount(take));
            stack.shrink(take);
            remaining -= take;
        }
        return List.copyOf(removed);
    }

    private record GraphNodeMaterials(
            List<ItemStack> materials,
            List<ExtractionLedger.ReservationToken> operationTokens,
            List<List<ItemStack>> virtualDebits,
            List<List<ItemStack>> producerDebits,
            List<ExtractionLedger.ReservationToken> reusableTokens) {
        GraphNodeMaterials {
            materials = List.copyOf(materials);
            operationTokens = List.copyOf(operationTokens);
            virtualDebits = List.copyOf(virtualDebits);
            producerDebits = List.copyOf(producerDebits);
            reusableTokens = List.copyOf(reusableTokens);
        }
    }

    @Nullable
    private GraphNodeMaterials reservePlannedCheckoutMaterials(
            ServerPlayer online, ExtractionLedger ledger,
            MaterialBroker.ReservationToken materialToken) {
        if (graphMaterials == null) return null;
        MaterialBroker.Checkout checkout = graphMaterials.checkout(materialToken);
        if (checkout.fragments().isEmpty()) return null;

        List<ItemStack> materials = new ArrayList<>();
        for (MaterialBroker.Fragment fragment : checkout.fragments()) {
            ItemStack planned = fragment.stack();
            if (fragment.source() instanceof MaterialSource.InitialPool) {
                ItemStack reserved = reserveExact(ledger, planned, planned.getCount(), online);
                if (reserved.isEmpty()) return null;
                materials.add(reserved);
            } else if (fragment.source() instanceof MaterialSource.ProducerOutput) {
                materials.add(planned.copy());
            } else {
                return null;
            }
        }
        return new GraphNodeMaterials(materials, List.of(), List.of(), List.of(), List.of());
    }

    @Nullable
    private GraphNodeMaterials reserveGraphNodeMaterials(
            IBatchDelegate delegate, List<IngredientSpec> specs, int executions,
            ServerPlayer online, ExtractionLedger ledger,
            MaterialBroker.ReservationToken materialToken) {
        int reservationMark = ledger.reservationMark();
        if (delegate instanceof ParallelCraftGroup group) {
            List<IngredientSpec> operationSpecs = group.getOperationMaterials();
            if (operationSpecs == null || operationSpecs.isEmpty()) {
                ledger.cancelReservationsSince(reservationMark);
                return null;
            }
            MaterialBroker.Checkout checkout = graphMaterials != null
                    ? graphMaterials.checkout(materialToken) : new MaterialBroker.Checkout(List.of());
            List<ItemStack> initialPool = new ArrayList<>(checkout.initialStacks());
            List<ItemStack> producerPool = new ArrayList<>(checkout.producerStacks());
            List<ItemStack> materials = new ArrayList<>();
            List<ExtractionLedger.ReservationToken> tokens = new ArrayList<>();
            List<List<ItemStack>> virtualDebits = new ArrayList<>();
            List<List<ItemStack>> producerDebits = new ArrayList<>();
            List<ItemStack> reusable = new ArrayList<>();
            List<List<Integer>> reusableEntryIds = new ArrayList<>();
            List<IngredientSpec> perOperationSpecs = new ArrayList<>();
            List<IBatchDelegate.MaterialReservationScope> scopes = group.getMaterialReservationScopes();
            List<Integer> reusableIndices = reusableMaterialIndices(scopes, operationSpecs.size());
            for (int i = 0; i < operationSpecs.size(); i++) {
                if (!reusableIndices.contains(i)) {
                    perOperationSpecs.add(operationSpecs.get(i));
                }
            }
            int workers = Math.min(group.getChildCount(), group.getTotalOperations());
            for (int worker = 0; worker < workers; worker++) {
                reusableEntryIds.add(new ArrayList<>());
            }
            for (int reusableIndex : reusableIndices) {
                IngredientSpec spec = operationSpecs.get(reusableIndex);
                for (int worker = 0; worker < workers; worker++) {
                    int reusableMark = ledger.reservationMark();
                    ItemStack material;
                    if (worker == 0) {
                        List<ItemStack> planned = reserveGraphMaterials(
                                List.of(spec), online, ledger, initialPool, producerPool);
                        if (planned == null || planned.size() != 1) {
                            ledger.cancelReservationsSince(reservationMark);
                            return null;
                        }
                        material = planned.get(0);
                    } else {
                        material = storageEndpoint != null
                                ? ledger.reserve(spec.ingredient(), spec.count(), storageEndpoint, online, null, null)
                                : ledger.reserve(spec.ingredient(), spec.count(), network, online, null, null);
                        if (material.isEmpty() || material.getCount() != spec.count()) {
                            ledger.cancelReservationsSince(reservationMark);
                            return null;
                        }
                    }
                    reusable.add(material);
                    reusableEntryIds.get(worker).addAll(ledger.tokenSince(reusableMark).entryIds());
                }
            }
            for (int operation = 0; operation < group.getTotalOperations(); operation++) {
                int mark = ledger.reservationMark();
                List<ItemStack> producerBefore = copyStacks(producerPool);
                List<ItemStack> slice = reserveGraphMaterials(
                        perOperationSpecs, online, ledger, initialPool, producerPool);
                if (slice == null) {
                    ledger.cancelReservationsSince(reservationMark);
                    return null;
                }
                List<ItemStack> full = new ArrayList<>();
                int consumedIndex = 0;
                int reusableIndex = operation % Math.max(1, workers);
                for (int i = 0; i < operationSpecs.size(); i++) {
                    if (reusableIndices.contains(i)) {
                        full.add(reusable.get(reusableIndex));
                        reusableIndex += workers;
                    } else {
                        full.add(slice.get(consumedIndex++));
                    }
                }
                materials.addAll(copyStacksKeepingEmpty(full));
                tokens.add(ledger.tokenSince(mark));
                virtualDebits.add(List.of());
                producerDebits.add(consumedFragments(producerBefore, producerPool));
            }
            requireGraphMaterialPoolsDrained(initialPool, producerPool);
            List<ExtractionLedger.ReservationToken> reusableTokens = reusableEntryIds.stream()
                    .map(ExtractionLedger.ReservationToken::new)
                    .toList();
            return new GraphNodeMaterials(materials, tokens, virtualDebits, producerDebits,
                    reusableTokens);
        }
        MaterialBroker.Checkout checkout = graphMaterials != null
                ? graphMaterials.checkout(materialToken) : new MaterialBroker.Checkout(List.of());
        List<ItemStack> initialPool = new ArrayList<>(checkout.initialStacks());
        List<ItemStack> producerPool = new ArrayList<>(checkout.producerStacks());
        List<IBatchDelegate.MaterialReservationScope> scopes = delegate.getMaterialReservationScopes();
        List<IngredientSpec> scaledSpecs = scaleGraphSpecsForExecutions(specs, scopes, executions);
        List<Integer> reusableIndices = reusableMaterialIndices(scopes, specs.size());
        List<IngredientSpec> reusableSpecs = new ArrayList<>();
        List<IngredientSpec> perOperationSpecs = new ArrayList<>();
        for (int i = 0; i < scaledSpecs.size(); i++) {
            if (reusableIndices.contains(i)) reusableSpecs.add(scaledSpecs.get(i));
            else perOperationSpecs.add(scaledSpecs.get(i));
        }
        int reusableMark = ledger.reservationMark();
        List<ItemStack> reusableMaterials = reserveGraphMaterials(
                reusableSpecs, online, ledger, initialPool, producerPool);
        List<ExtractionLedger.ReservationToken> reusableTokens = reusableMaterials == null
                ? List.of() : List.of(ledger.tokenSince(reusableMark));
        List<ItemStack> consumedMaterials = reusableMaterials == null ? null : reserveGraphMaterials(
                perOperationSpecs, online, ledger, initialPool, producerPool);
        if (reusableMaterials == null || consumedMaterials == null) {
            ledger.cancelReservationsSince(reservationMark);
            return null;
        }
        List<ItemStack> materials = new ArrayList<>(scaledSpecs.size());
        int reusableIndex = 0;
        int consumedIndex = 0;
        for (int i = 0; i < scaledSpecs.size(); i++) {
            if (reusableIndices.contains(i)) materials.add(reusableMaterials.get(reusableIndex++));
            else materials.add(consumedMaterials.get(consumedIndex++));
        }
        requireGraphMaterialPoolsDrained(initialPool, producerPool);
        return new GraphNodeMaterials(materials, List.of(), List.of(), List.of(), reusableTokens);
    }

    private static void requireGraphMaterialPoolsDrained(
            List<ItemStack> initialPool, List<ItemStack> producerPool) {
        if (!graphMaterialPoolsDrained(initialPool, producerPool)) {
            throw new IllegalStateException(
                    "runtime material specs did not consume the complete graph checkout");
        }
    }

    static boolean graphMaterialPoolsDrained(
            List<ItemStack> initialPool, List<ItemStack> producerPool) {
        return initialPool.stream().allMatch(stack -> stack == null || stack.isEmpty())
                && producerPool.stream().allMatch(stack -> stack == null || stack.isEmpty());
    }

    static List<IngredientSpec> scaleGraphSpecsForExecutions(
            List<IngredientSpec> specs,
            List<IBatchDelegate.MaterialReservationScope> scopes,
            int executions) {
        int multiplier = Math.max(1, executions);
        List<IngredientSpec> scaledSpecs = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            IngredientSpec spec = specs.get(i);
            boolean reusable = i < scopes.size()
                    && scopes.get(i) == IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE;
            int count = reusable ? spec.count() : StepExecutor.mulCount(spec.count(), multiplier);
            scaledSpecs.add(new IngredientSpec(spec.ingredient(), count, spec.role()));
        }
        return List.copyOf(scaledSpecs);
    }

    static List<Integer> reusableMaterialIndices(
            List<IBatchDelegate.MaterialReservationScope> scopes, int specCount) {
        List<Integer> indices = new ArrayList<>();
        int limit = Math.min(Math.max(0, specCount), scopes.size());
        for (int i = 0; i < limit; i++) {
            if (scopes.get(i) == IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE) {
                indices.add(i);
            }
        }
        return List.copyOf(indices);
    }

    private static List<ItemStack> consumedFragments(
            List<ItemStack> before, List<ItemStack> after) {
        List<ItemStack> consumed = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            ItemStack original = before.get(i);
            int remaining = i < after.size() ? after.get(i).getCount() : 0;
            int count = original.getCount() - remaining;
            if (count > 0) consumed.add(original.copyWithCount(count));
        }
        return List.copyOf(consumed);
    }

    /** Reserve initial allocations physically and checkout exact producer fragments. */
    @Nullable
    private List<ItemStack> reserveGraphMaterials(
            List<IngredientSpec> specs, ServerPlayer online, ExtractionLedger ledger,
            List<ItemStack> initialPool, List<ItemStack> producerPool) {
        int reservationMark = ledger.reservationMark();
        List<ItemStack> reserved;
        try {
            reserved = StackPoolTransaction.execute(initialPool, producerPool,
                    (workingInitial, workingProducer) -> reserveGraphMaterialsInPlace(
                            specs, online, ledger, workingInitial, workingProducer));
        } catch (RuntimeException e) {
            ledger.cancelReservationsSince(reservationMark);
            throw e;
        }
        if (reserved == null) ledger.cancelReservationsSince(reservationMark);
        return reserved;
    }

    @Nullable
    private List<ItemStack> reserveGraphMaterialsInPlace(
            List<IngredientSpec> specs, ServerPlayer online, ExtractionLedger ledger,
            List<ItemStack> initialPool, List<ItemStack> producerPool) {
        List<ItemStack> materials = new ArrayList<>(specs.size());
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) {
                materials.add(ItemStack.EMPTY);
                continue;
            }
            int remaining = spec.count();
            ItemStack combined = ItemStack.EMPTY;
            for (ItemStack produced : producerPool) {
                if (remaining <= 0) break;
                if (produced.isEmpty() || !IngredientMatcher.test(spec.ingredient(), produced)) continue;
                if (!combined.isEmpty() && !ItemStack.isSameItemSameTags(combined, produced)) continue;
                int take = Math.min(remaining, produced.getCount());
                if (combined.isEmpty()) {
                    combined = produced.copyWithCount(take);
                } else {
                    combined.grow(take);
                }
                produced.shrink(take);
                remaining -= take;
            }
            if (remaining > 0) {
                // MaterialBroker checkout selected the item type from the planning
                // snapshot. Preserve exact NBT when the ingredient requires it;
                // ordinary tagless ingredients may carry harmless runtime metadata.
                ItemStack planned = findMatching(
                        initialPool, spec.ingredient(), remaining, true);
                if (planned.isEmpty() || planned.getCount() != remaining) return null;
                int reservationMark = ledger.reservationMark();
                ItemStack exactTemplate = combined.isEmpty() ? planned : combined;
                boolean exactNbt = requiresExactGraphReservation(spec.ingredient());
                ItemStack initial = reserveExact(ledger, exactTemplate, remaining, online);
                if (initial.isEmpty() && !exactNbt) {
                    // Keep the fallback constrained to the concrete item selected
                    // by the graph; a broad tag ingredient must not consume another
                    // item type than the one admitted by the graph.
                    initial = reserveIngredient(ledger,
                            Ingredient.of(new ItemStack(planned.getItem())), remaining, online);
                }
                if (initial.isEmpty()) {
                    RSIntegrationMod.LOGGER.info(ctx.format(
                            "Graph material reservation failed: recipe={} step={} ingredient={} planned={} exactNbt={} availability={}"),
                            currentRecipeForLogging(), currentStepIdx,
                            CraftPacketUtils.describeIngredient(spec.ingredient()),
                            describeStackForLogging(planned), exactNbt,
                            ledger.describeExactAvailability(exactTemplate, online));
                    ledger.cancelReservationsSince(reservationMark);
                    return null;
                }
                boolean consumed = exactNbt
                        ? takeExactMatching(initialPool, initial, remaining)
                        : takeMatchingItem(initialPool, planned, remaining);
                if (!consumed) {
                    ledger.cancelReservationsSince(reservationMark);
                    return null;
                }
                if (combined.isEmpty()) {
                    combined = initial.copyWithCount(remaining);
                } else if (ItemStack.isSameItemSameTags(combined, initial)) {
                    combined.grow(remaining);
                } else {
                    ledger.cancelReservationsSince(reservationMark);
                    return null;
                }
            }
            materials.add(combined);
        }
        return materials;
    }

    private static boolean takeMatchingItem(List<ItemStack> pool, ItemStack planned, int count) {
        if (planned == null || planned.isEmpty() || count <= 0) return false;
        int available = 0;
        for (ItemStack stack : pool) {
            if (!stack.isEmpty() && stack.getItem() == planned.getItem()) available += stack.getCount();
        }
        if (available < count) return false;
        int remaining = count;
        for (ItemStack stack : pool) {
            if (remaining <= 0) break;
            if (stack.isEmpty() || stack.getItem() != planned.getItem()) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        return remaining == 0;
    }

    private String currentRecipeForLogging() {
        return steps.isEmpty() ? "unknown"
                : steps.get(Math.min(Math.max(0, currentStepIdx), steps.size() - 1))
                .recipeId().toString();
    }

    private static String describeStackForLogging(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                + " x" + stack.getCount() + (stack.hasTag() ? " tag=" + stack.getTag() : "");
    }

    static boolean requiresExactGraphReservation(Ingredient ingredient) {
        return ingredient != null && !ingredient.isEmpty()
                && IngredientMatcher.requiresNbt(ingredient);
    }

    static ItemStack takeMatching(
            List<ItemStack> pool, Ingredient ingredient, int count, boolean exactNbt) {
        ItemStack selected = findMatching(pool, ingredient, count, exactNbt);
        if (selected.isEmpty()) return ItemStack.EMPTY;

        int remaining = count;
        for (ItemStack stack : pool) {
            if (remaining <= 0) break;
            if (stack.isEmpty() || !IngredientMatcher.test(ingredient, stack)) continue;
            if (exactNbt && !ItemStack.isSameItemSameTags(selected, stack)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        return selected.copyWithCount(count);
    }

    static ItemStack findMatching(
            List<ItemStack> pool, Ingredient ingredient, int count, boolean exactNbt) {
        ItemStack selected = ItemStack.EMPTY;
        int available = 0;
        for (ItemStack stack : pool) {
            if (stack.isEmpty() || !IngredientMatcher.test(ingredient, stack)) continue;
            if (selected.isEmpty()) {
                selected = stack.copyWithCount(1);
            } else if (exactNbt && !ItemStack.isSameItemSameTags(selected, stack)) {
                continue;
            }
            available += stack.getCount();
            if (available >= count) break;
        }
        if (selected.isEmpty() || available < count) return ItemStack.EMPTY;
        return selected.copyWithCount(count);
    }

    static boolean takeExactMatching(List<ItemStack> pool, ItemStack template, int count) {
        int available = 0;
        for (ItemStack stack : pool) {
            if (ItemStack.isSameItemSameTags(stack, template)) available += stack.getCount();
        }
        if (available < count) return false;
        int remaining = count;
        for (ItemStack stack : pool) {
            if (remaining <= 0) break;
            if (!ItemStack.isSameItemSameTags(stack, template)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        return true;
    }

    private static String describeOutputCheck(OutputDeclaration output, int matchedCount) {
        return "{port=" + output.id() + ",kind=" + output.kind()
                + ",material=" + describeMaterialForLogging(output.material())
                + ",expected=" + output.quantity() + ",matched=" + matchedCount + "}";
    }

    private static String describeOutputDeclarations(List<OutputDeclaration> outputs) {
        List<String> descriptions = new ArrayList<>(outputs.size());
        for (OutputDeclaration output : outputs) {
            descriptions.add("{port=" + output.id() + ",kind=" + output.kind()
                    + ",material=" + describeMaterialForLogging(output.material())
                    + ",quantity=" + output.quantity() + "}");
        }
        return descriptions.toString();
    }

    private static String describeMaterialForLogging(MaterialKey material) {
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(material.item());
        return id + (material.tag() == null ? "" : " tag=" + material.tag());
    }

    private static String describeStacksForLogging(List<ItemStack> stacks) {
        List<String> descriptions = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) descriptions.add(describeStackForLogging(stack));
        }
        return descriptions.toString();
    }

    private boolean isGraphTerminalNode(NodeId nodeId) {
        return graph != null && !graph.topologicalOrder().isEmpty()
                && graph.topologicalOrder().get(graph.topologicalOrder().size() - 1).equals(nodeId);
    }

    private int stepIndex(NodeId nodeId) {
        for (int i = 0; i < graph.topologicalOrder().size(); i++) {
            if (graph.topologicalOrder().get(i).equals(nodeId)) return i;
        }
        throw new IllegalArgumentException("Unknown graph node " + nodeId);
    }

    private CraftingResolver.ResolutionStep graphStep(NodeId nodeId) {
        CraftNode node = graphNodes.get(nodeId);
        if (node == null) throw new IllegalArgumentException("Unknown graph node " + nodeId);
        return ExecutionEquivalence.projectStep(node);
    }

    public boolean isDone() { return state == State.COMPLETED || state == State.ABORTED; }
    public boolean isAborted() { return state == State.ABORTED; }
    public State state() { return state; }
    public String abortReason() { return abortReason; }
    @Nullable public TerminationCoordinator.Report terminationReport() { return terminationReport; }
    /** @return the stable identity of this craft run */
    public UUID getCraftId() { return craftId; }
    /** @return the player's UUID (migration from stale ServerPlayer reference) */
    public UUID getPlayerId() { return playerId; }
    public int currentStep() { return currentStepIdx; }
    public int stepsCount() { return steps.size(); }
    public boolean isGraphExecution() { return useGraphExecution; }

    /**
     * Build the current server-authoritative progress view for a status request.
     * A status response is an outgoing progress event, so it advances the same
     * monotonic sequence used by periodic updates. This matters on a freshly
     * started craft: the preceding CraftStartedPacket installs sequence 0, and
     * a status snapshot also numbered 0 would correctly be rejected as stale.
     */
    public CraftProgressSnapshot nextStatusSnapshot() {
        return buildProgressSnapshot(isDone());
    }

    private CraftProgressSnapshot buildProgressSnapshot(boolean terminal) {
        int total = useGraphExecution && graph != null
                ? graph.topologicalOrder().size() : steps.size();
        int completed = useGraphExecution && graphScheduler != null
                ? (int) graphScheduler.countSucceeded() : currentStepIdx;
        int running = graphExecutor != null ? graphExecutor.runningCount()
                : (currentDelegate != null ? 1 : 0);
        CraftProgressSnapshot.Result progressResult = progressResult(terminal);
        CraftProgressSnapshot.Reason progressReason = progressReason(progressResult);
        int sequence = terminal ? CraftProgressSnapshot.TERMINAL_SEQUENCE : ++progressSequence;
        return new CraftProgressSnapshot(craftId, sequence, progressResult, progressReason,
                completed, total, running, abortReason.isEmpty() ? null : abortReason,
                buildNodeProgress());
    }

    private CraftProgressSnapshot.Result progressResult(boolean terminal) {
        if (terminal || state == State.COMPLETED || state == State.ABORTED) {
            if (state == State.COMPLETED) return CraftProgressSnapshot.Result.SUCCEEDED;
            if (terminalCause == TerminationCoordinator.Cause.CANCELLED) {
                return CraftProgressSnapshot.Result.CANCELLED;
            }
            return CraftProgressSnapshot.Result.FAILED;
        }
        if (graphScheduler != null && graphScheduler.isStopping()) {
            return CraftProgressSnapshot.Result.STOPPING;
        }
        if (state == State.WAITING_MOD || state == State.WAITING_PLAYER_TRANSFORMATION
                || waitingForMachineLease) {
            return CraftProgressSnapshot.Result.WAITING;
        }
        return CraftProgressSnapshot.Result.RUNNING;
    }

    private CraftProgressSnapshot.Reason progressReason(CraftProgressSnapshot.Result result) {
        if (result == CraftProgressSnapshot.Result.CANCELLED) {
            return CraftProgressSnapshot.Reason.PLAYER_CANCELLED;
        }
        if (terminalCause == TerminationCoordinator.Cause.OFFLINE) {
            return CraftProgressSnapshot.Reason.PLAYER_OFFLINE;
        }
        if (terminalCause == TerminationCoordinator.Cause.SERVER_STOP) {
            return CraftProgressSnapshot.Reason.SERVER_STOP;
        }
        if (terminalCause == TerminationCoordinator.Cause.INTERNAL_ERROR) {
            return CraftProgressSnapshot.Reason.INTERNAL_ERROR;
        }
        String normalized = abortReason.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("timeout") || normalized.contains("exceeded global")) {
            return CraftProgressSnapshot.Reason.TIMEOUT;
        }
        if (normalized.contains("missing") || normalized.contains("material")) {
            return CraftProgressSnapshot.Reason.MATERIAL_EXTRACTION_FAILED;
        }
        if (normalized.contains("start") || normalized.contains("rejected")) {
            return CraftProgressSnapshot.Reason.START_REJECTED;
        }
        if (normalized.contains("output")) {
            return CraftProgressSnapshot.Reason.OUTPUT_MISSING;
        }
        if (result == CraftProgressSnapshot.Result.FAILED) {
            return CraftProgressSnapshot.Reason.UNKNOWN;
        }
        if ((state == State.WAITING_MOD && currentDelegate != null) || waitingForMachineLease) {
            return CraftProgressSnapshot.Reason.MACHINE_BUSY;
        }
        return CraftProgressSnapshot.Reason.NONE;
    }

    private List<CraftProgressSnapshot.NodeProgress> buildNodeProgress() {
        if (!useGraphExecution || graph == null || graphScheduler == null) {
            return buildFlatNodeProgress();
        }
        List<CraftProgressSnapshot.NodeProgress> result = new ArrayList<>(graph.topologicalOrder().size());
        for (NodeId nodeId : graph.topologicalOrder()) {
            CraftNode node = graphNodes.get(nodeId);
            DagScheduler.NodeState schedulerState = graphScheduler.state(nodeId);
            CraftNodeRuntime runtime = nodeRuntimes.get(nodeId);
            int totalOps = runtime != null ? runtime.totalOperations()
                    : node != null ? Math.max(1, node.executions()) : 1;
            int completedOps = runtime != null ? runtime.completedOperations()
                    : schedulerState == DagScheduler.NodeState.SUCCEEDED ? totalOps : 0;
            int runningOps = runtime != null ? runtime.runningOperations() : 0;
            String detail = runtime != null && runtime.failureReason() != null
                    ? runtime.failureReason() : graphFailureDetails.getOrDefault(nodeId, "");
            result.add(new CraftProgressSnapshot.NodeProgress(nodeId.value(),
                    progressNodeState(schedulerState),
                    node != null ? node.recipeId().toString() : "",
                    node != null ? node.modTypeId() : "",
                    displayOutput(node),
                    completedOps, totalOps, runningOps,
                    runtime != null ? runtime.machineLabel() : "",
                    progressReasonForDetail(detail), detail,
                    runtime != null && runtime.isDraining()));
        }
        return List.copyOf(result);
    }

    private List<CraftProgressSnapshot.NodeProgress> buildFlatNodeProgress() {
        List<CraftProgressSnapshot.NodeProgress> result = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            CraftingResolver.ResolutionStep step = steps.get(i);
            CraftProgressSnapshot.NodeState nodeState;
            if (i < currentStepIdx || state == State.COMPLETED) {
                nodeState = CraftProgressSnapshot.NodeState.SUCCEEDED;
            } else if (i > currentStepIdx) {
                nodeState = CraftProgressSnapshot.NodeState.BLOCKED;
            } else if (state == State.ABORTED) {
                nodeState = CraftProgressSnapshot.NodeState.FAILED;
            } else if (currentDelegate != null || stepRemaining > 0 || state == State.WAITING_MOD
                    || state == State.WAITING_PLAYER_TRANSFORMATION) {
                nodeState = CraftProgressSnapshot.NodeState.RUNNING;
            } else {
                nodeState = CraftProgressSnapshot.NodeState.READY;
            }
            int totalOps = Math.max(1, step.executions());
            int completedOps = i < currentStepIdx || state == State.COMPLETED ? totalOps : 0;
            if (i == currentStepIdx && stepRemaining > 0
                    && !(currentDelegate instanceof ParallelCraftGroup)) {
                completedOps = completedFlatOperations(totalOps, stepRemaining);
            }
            int runningOps = i == currentStepIdx && currentDelegate != null
                    ? Math.min(totalOps, currentDelegate instanceof ParallelCraftGroup group
                            ? group.getRunningOperations() : 1) : 0;
            if (i == currentStepIdx && currentDelegate instanceof ParallelCraftGroup group) {
                completedOps = group.getCompletedOperations();
                totalOps = group.getTotalOperations();
            }
            String detail = i == currentStepIdx ? abortReason : "";
            result.add(new CraftProgressSnapshot.NodeProgress(i, nodeState,
                    step.recipeId().toString(), step.modType().id(), displayOutput(step),
                    completedOps, totalOps, runningOps, i == currentStepIdx ? flatMachineLabel() : "",
                    progressReasonForDetail(detail), detail,
                    i == currentStepIdx && currentDelegate instanceof ParallelCraftGroup group
                            && group.isDraining()));
        }
        return List.copyOf(result);
    }

    static int completedFlatOperations(int totalOperations, int remainingOperations) {
        int total = Math.max(1, totalOperations);
        return Math.max(0, Math.min(total, total - Math.max(0, remainingOperations)));
    }

    private static ItemStack displayOutput(@Nullable CraftNode node) {
        if (node == null) return ItemStack.EMPTY;
        return node.outputs().stream()
                .filter(output -> output.kind() == OutputKind.PRIMARY
                        || output.kind() == OutputKind.DYNAMIC)
                .findFirst()
                .map(output -> output.material().toStack(
                        Math.max(1, output.quantity() / Math.max(1, node.executions()))))
                .orElseGet(() -> node.syntheticOutput() == null
                        ? ItemStack.EMPTY : node.syntheticOutput().copy());
    }

    private ItemStack displayOutput(CraftingResolver.ResolutionStep step) {
        if (step.syntheticOutput() != null && !step.syntheticOutput().isEmpty()) {
            return step.syntheticOutput().copy();
        }
        return server.getRecipeManager().byKey(step.recipeId())
                .map(recipe -> ModRecipeHandlers.tryGetResultItem(
                        recipe, server.overworld().registryAccess()))
                .orElse(ItemStack.EMPTY);
    }

    private String flatMachineLabel() {
        if (currentDelegate instanceof ParallelCraftGroup group) return group.machineLabel();
        MachineLeaseRegistry.Lease lease = flatOperationSession == null
                ? null : flatOperationSession.machineLease();
        if (lease == null) return "";
        MachineLeaseRegistry.MachineKey machine = lease.machine();
        return machine.dimension() + "@" + machine.position().toShortString();
    }

    private static CraftProgressSnapshot.Reason progressReasonForDetail(String detail) {
        if (detail == null || detail.isEmpty()) return CraftProgressSnapshot.Reason.NONE;
        String normalized = detail.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("busy")) return CraftProgressSnapshot.Reason.MACHINE_BUSY;
        if (normalized.contains("network is null")
                || normalized.contains("network unavailable")
                || normalized.contains("no usable rs network")
                || (normalized.contains("getitemstoragetracker") && normalized.contains("null"))) {
            return CraftProgressSnapshot.Reason.NETWORK_UNAVAILABLE;
        }
        if (normalized.contains("no bound") || normalized.contains("not bound")) {
            return CraftProgressSnapshot.Reason.NO_BOUND_MACHINE;
        }
        if (normalized.contains("slot mismatch")
                || normalized.contains("input conflict")
                || normalized.contains("cannot merge")
                || normalized.contains("mixed variant")) {
            return CraftProgressSnapshot.Reason.INPUT_CONFLICT;
        }
        if (normalized.contains("insufficient wissen")
                || normalized.contains("insufficient xp")
                || normalized.contains("insufficient source")
                || normalized.contains("insufficient mana")
                || normalized.contains("insufficient aureal")
                || normalized.contains("insufficient souls")
                || normalized.contains("insufficient blood")) {
            return CraftProgressSnapshot.Reason.SPECIAL_RESOURCE_INSUFFICIENT;
        }
        if (normalized.contains("unloaded") || normalized.contains("chunk")) {
            return CraftProgressSnapshot.Reason.CHUNK_UNLOADED;
        }
        if (normalized.contains("budget") || normalized.contains("capacity")) {
            return CraftProgressSnapshot.Reason.OPERATION_BUDGET;
        }
        if (normalized.contains("lease") || normalized.contains("conflict")) {
            return CraftProgressSnapshot.Reason.RESOURCE_CONFLICT;
        }
        if (normalized.contains("contract") || normalized.contains("delegate validation")) {
            return CraftProgressSnapshot.Reason.CONTRACT_INCOMPATIBLE;
        }
        if (normalized.contains("material") || normalized.contains("missing")) {
            return CraftProgressSnapshot.Reason.MATERIAL_EXTRACTION_FAILED;
        }
        if (normalized.contains("start") || normalized.contains("rejected")) {
            return CraftProgressSnapshot.Reason.START_REJECTED;
        }
        if (normalized.contains("output")) return CraftProgressSnapshot.Reason.OUTPUT_MISSING;
        if (normalized.contains("timeout")) return CraftProgressSnapshot.Reason.TIMEOUT;
        return CraftProgressSnapshot.Reason.UNKNOWN;
    }

    private static CraftProgressSnapshot.NodeState progressNodeState(
            DagScheduler.NodeState state) {
        if (state == null) return CraftProgressSnapshot.NodeState.UNKNOWN;
        return switch (state) {
            case BLOCKED -> CraftProgressSnapshot.NodeState.BLOCKED;
            case READY -> CraftProgressSnapshot.NodeState.READY;
            case RUNNING -> CraftProgressSnapshot.NodeState.RUNNING;
            case SUCCEEDED -> CraftProgressSnapshot.NodeState.SUCCEEDED;
            case FAILED -> CraftProgressSnapshot.NodeState.FAILED;
            case CANCELLED -> CraftProgressSnapshot.NodeState.CANCELLED;
        };
    }

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
        // Heart is now physically in the player's slot, not virtualInventory -
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
        // Tainted heart is back in virtualInventory and this taint step is done -
        // settled boundary owed to the player on any later abort.
        snapshotCommittedVirtual();
        MaterialSources.invalidateFor(player);
        return false;
    }

    private static boolean hasEquippedCursedRing(ServerPlayer player) {
        try {
            for (ItemStack stack : com.huanghuang.rsintegration.util.CuriosAccess
                    .equippedStacks(player)) {
                if (new ResourceLocation("enigmaticlegacy", "cursed_ring")
                        .equals(ForgeRegistries.ITEMS.getKey(stack.getItem()))) return true;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI] Failed to inspect Curios for Cursed Ring", e);
        }
        return false;
    }

    //  vanilla batch execution

    /** Execute one bounded slice of consecutive vanilla steps. */
    private int executeVanillaBatch(int startIdx, ServerPlayer online, int operationBudget) {
        VanillaBatchSlice slice = planVanillaBatchSlice(
                steps, startIdx, stepRemaining, operationBudget);
        stepRemaining = slice.remainingExecutions();
        if (!slice.steps().isEmpty()) {
            executeVanillaStepsInline(slice.steps(), online);
        }
        return slice.nextStepIndex();
    }

    static VanillaBatchSlice planVanillaBatchSlice(
            List<CraftingResolver.ResolutionStep> sourceSteps,
            int startIdx, int currentRemaining, int operationBudget) {
        int budget = Math.max(1, operationBudget);
        int i = Math.max(0, startIdx);
        int remaining = Math.max(0, currentRemaining);
        List<CraftingResolver.ResolutionStep> sliceSteps = new ArrayList<>();
        while (i < sourceSteps.size() && budget > 0) {
            CraftingResolver.ResolutionStep step = sourceSteps.get(i);
            if (step.modType() != ModType.GENERIC
                    || step.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP)) {
                break;
            }
            int stepExecutions = i == startIdx && remaining > 0
                    ? remaining : step.executions();
            int sliceExecutions = Math.min(stepExecutions, budget);
            sliceSteps.add(copyWithExecutions(step, sliceExecutions));
            budget -= sliceExecutions;
            stepExecutions -= sliceExecutions;
            if (stepExecutions > 0) {
                return new VanillaBatchSlice(List.copyOf(sliceSteps), i, stepExecutions);
            }
            remaining = 0;
            i++;
        }
        return new VanillaBatchSlice(List.copyOf(sliceSteps), i, 0);
    }

    private static CraftingResolver.ResolutionStep copyWithExecutions(
            CraftingResolver.ResolutionStep step, int executions) {
        return new CraftingResolver.ResolutionStep(
                step.recipeId(), step.modType(), step.recipeTypeId(),
                step.alternativeIds(), step.alternativeModTypes(), step.inferMode(),
                executions, step.syntheticInput(), step.syntheticOutput());
    }

    record VanillaBatchSlice(
            List<CraftingResolver.ResolutionStep> steps,
            int nextStepIndex,
            int remainingExecutions) {}

    boolean isWaitingForVanillaBudget() {
        return waitingForVanillaBudget;
    }

    private static int configuredAtomicVanillaGraphLimit() {
        try {
            return Math.max(1, Math.min(
                    RSIntegrationConfig.CRAFTING_VANILLA_OPERATIONS_PER_TICK.get(),
                    RSIntegrationConfig.CRAFTING_GLOBAL_VANILLA_OPERATIONS_PER_TICK.get()));
        } catch (Exception ignored) {
            return Math.min(RSIntegrationConfig.DEFAULT_CRAFTING_VANILLA_OPERATIONS_PER_TICK,
                    RSIntegrationConfig.DEFAULT_CRAFTING_GLOBAL_VANILLA_OPERATIONS_PER_TICK);
        }
    }

    private static int configuredOperationsPerDispatch() {
        try {
            return Math.max(1, RSIntegrationConfig.CRAFTING_OPERATIONS_PER_DISPATCH.get());
        } catch (Exception ignored) {
            return RSIntegrationConfig.DEFAULT_CRAFTING_OPERATIONS_PER_DISPATCH;
        }
    }

    static boolean requiresFlatExecutionForOversizedNode(
            List<CraftingResolver.ResolutionStep> steps, int vanillaOperationLimit,
            int dispatchOperationLimit) {
        int vanillaLimit = Math.max(1, vanillaOperationLimit);
        int machineLimit = Math.max(1, dispatchOperationLimit);
        return steps.stream().anyMatch(step -> step.executions()
                > (step.modType() == ModType.GENERIC ? vanillaLimit : machineLimit));
    }

    /**
     * Execute vanilla crafting steps inline, using the chain's virtual inventory
     * and ledger so intermediate outputs feed forward across the entire chain.
     */
    private boolean executeVanillaStepsInline(List<CraftingResolver.ResolutionStep> vanillaSteps,
                                              ServerPlayer online) {
        return executeVanillaStepsInline(vanillaSteps, online, virtualInventory, ledger, true);
    }

    private boolean executeVanillaStepsInline(List<CraftingResolver.ResolutionStep> vanillaSteps,
                                              ServerPlayer online,
                                              List<ItemStack> workingInventory) {
        return executeVanillaStepsInline(vanillaSteps, online, workingInventory, ledger, true);
    }

    private boolean executeVanillaStepsInline(List<CraftingResolver.ResolutionStep> vanillaSteps,
                                              ServerPlayer online,
                                              List<ItemStack> workingInventory,
                                              ExtractionLedger executionLedger,
                                              boolean allowPhysicalFallback) {
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
                List<IngredientSpec> specs = CraftPacketUtils.extractCraftingIngredientSpecs(cr);
                ItemStack terminalOutput = terminalOutputFor(
                        stepId, cr, overworld.registryAccess());
                if (!terminalOutput.isEmpty()
                        && !SelfAmplifyingRecipePolicy.isSelfAmplifying(specs, terminalOutput)) {
                    specs = specs.stream()
                            .map(spec -> SelfAmplifyingRecipePolicy
                                    .excludeNonProductiveSelfCandidate(spec, terminalOutput))
                            .toList();
                }
                for (int execution = 0; execution < executions; execution++) {
                    if (!executeCraftingOnceInline(cr, specs, stepId, online,
                            workingInventory, executionLedger, allowPhysicalFallback,
                            overworld.registryAccess())) {
                        return false;
                    }
                }
            } else {
                // Non-crafting GENERIC recipe (e.g. sawmill, custom mod type)
                List<IngredientSpec> specs =
                        CraftPacketUtils.extractIngredientSpecs(recipe);
                if (specs == null || specs.isEmpty()) continue;
                List<ItemStack> consumedInputs = new ArrayList<>();

                for (IngredientSpec spec : specs) {
                    if (spec.isEmpty()) continue;
                    int stillNeeded = CraftPacketUtils.requiredCount(spec, executions);
                    boolean captured = false;
                    var iter = workingInventory.iterator();
                    while (iter.hasNext() && stillNeeded > 0) {
                        ItemStack vi = iter.next();
                        if (spec.ingredient().test(vi)) {
                            int take = Math.min(stillNeeded, vi.getCount());
                            if (!captured) consumedInputs.add(vi.copyWithCount(1));
                            captured = true;
                            vi.shrink(take);
                            stillNeeded -= take;
                            if (vi.isEmpty()) iter.remove();
                        }
                    }
                    if (stillNeeded > 0) {
                        ItemStack reserved = ItemStack.EMPTY;
                        if (allowPhysicalFallback) {
                            // A null network is the normal standalone-backend path.
                            // Do not invoke an RS-typed method in that case: besides
                            // being invalid, linking that signature crashes when RS is
                            // absent from the classpath.
                            reserved = reserveIngredient(executionLedger, spec.ingredient(), stillNeeded, online);
                            if (reserved.isEmpty()) {
                                reserved = executionLedger.reserveFromInventory(
                                        spec.ingredient(), stillNeeded, online);
                            }
                        }
                        if (reserved.isEmpty()) {
                            logMissingIngredient(spec.ingredient(), stepId);
                            logVirtualInventory("at failure for step " + stepId);
                            logLedgerState(executionLedger);
                            if (allowPhysicalFallback) {
                                abort("Missing: " + describeIngredientSafe(spec.ingredient()),
                                        Component.translatable("rsi.async.abort.missing_material",
                                                nameIngredientSafe(spec.ingredient())));
                            }
                            return false;
                        }
                        if (!captured) consumedInputs.add(reserved.copyWithCount(1));
                    }
                }

                ItemStack result;
                if (recipe instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe smithing) {
                    result = SmithingRecipeHandler.assembleTransform(
                            smithing, consumedInputs, server.overworld().registryAccess());
                } else {
                    result = ModRecipeHandlers.tryGetResultItem(
                            recipe, server.overworld().registryAccess());
                }
                if (!result.isEmpty()) {
                    addToInventory(workingInventory,
                            result.copyWithCount(StepExecutor.mulCount(result.getCount(), executions)));
                }
                for (ItemStack secondary : ModRecipeHandlers.tryGetSecondaryOutputs(recipe, server.overworld().registryAccess())) {
                    addToInventory(workingInventory,
                            secondary.copyWithCount(StepExecutor.mulCount(secondary.getCount(), executions)));
                }
                for (IngredientSpec spec : specs) {
                    if (spec.isEmpty()) continue;
                    for (ItemStack stack : spec.ingredient().getItems()) {
                        if (stack.isEmpty()) continue;
                        try {
                            ItemStack remainder = stack.getCraftingRemainingItem();
                            if (!remainder.isEmpty()) {
                                addToInventory(workingInventory, remainder.copyWithCount(
                                        CraftPacketUtils.requiredCount(spec, executions)));
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

    private ItemStack terminalOutputFor(ResourceLocation stepId,
                                        net.minecraft.world.item.crafting.CraftingRecipe recipe,
                                        net.minecraft.core.RegistryAccess registryAccess) {
        if (steps.isEmpty() || !steps.get(steps.size() - 1).recipeId().equals(stepId)) {
            return ItemStack.EMPTY;
        }
        if (targetOutput != null && !targetOutput.isEmpty()) return targetOutput.copyWithCount(1);
        ItemStack declared = ModRecipeHandlers.tryGetResultItem(recipe, registryAccess);
        return declared.isEmpty() ? ItemStack.EMPTY : declared.copyWithCount(1);
    }

    private boolean executeCraftingOnceInline(
            net.minecraft.world.item.crafting.CraftingRecipe recipe,
            List<IngredientSpec> specs, ResourceLocation stepId, ServerPlayer online,
            List<ItemStack> workingInventory, ExtractionLedger executionLedger,
            boolean allowPhysicalFallback,
            net.minecraft.core.RegistryAccess registryAccess) {
        Map<Integer, ItemStack> modifiedSlots = new HashMap<>();
        ItemStack[] consumed = new ItemStack[Math.min(specs.size(), 9)];

        for (int ingIdx = 0; ingIdx < specs.size(); ingIdx++) {
            IngredientSpec spec = specs.get(ingIdx);
            if (spec.isEmpty()) continue;
            Ingredient ingredient = spec.ingredient();
            int stillNeeded = CraftPacketUtils.requiredCount(spec, 1);
            boolean captured = false;
            for (int i = 0; i < workingInventory.size() && stillNeeded > 0; i++) {
                ItemStack available = workingInventory.get(i);
                if (available.isEmpty() || !ingredient.test(available)) continue;
                modifiedSlots.putIfAbsent(i, available.copy());
                if (!captured && ingIdx < consumed.length) {
                    consumed[ingIdx] = available.copyWithCount(1);
                    captured = true;
                }
                int take = Math.min(stillNeeded, available.getCount());
                available.shrink(take);
                stillNeeded -= take;
            }
            if (stillNeeded <= 0) continue;

            ItemStack reserved = ItemStack.EMPTY;
            if (allowPhysicalFallback) {
                reserved = reserveIngredient(executionLedger, ingredient, stillNeeded, online);
                if (reserved.isEmpty()) {
                    reserved = executionLedger.reserveFromInventory(ingredient, stillNeeded, online);
                }
            }
            if (reserved.isEmpty()) {
                modifiedSlots.forEach((index, originalStack) -> {
                    if (index < workingInventory.size()) {
                        workingInventory.set(index, originalStack);
                    } else {
                        workingInventory.add(originalStack);
                    }
                });
                logMissingIngredient(ingredient, stepId);
                logVirtualInventory("at failure for step " + stepId);
                logLedgerState(executionLedger);
                if (allowPhysicalFallback) {
                    abort("Missing: " + describeIngredientSafe(ingredient),
                            Component.translatable("rsi.async.abort.missing_material",
                                    nameIngredientSafe(ingredient)));
                }
                return false;
            }
            if (!captured && ingIdx < consumed.length) {
                consumed[ingIdx] = reserved.copyWithCount(1);
            }
        }

        ItemStack result = CraftPacketUtils.assembleCraftingOutput(recipe, consumed, online);
        if (result.isEmpty()) {
            result = ModRecipeHandlers.tryGetResultItem(recipe, registryAccess);
        }
        if (!result.isEmpty()) addToInventory(workingInventory, result);

        for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(recipe, consumed)) {
            int remainderExecutions = CraftPacketUtils.remainderExecutions(remainder, specs, 1);
            addToInventory(workingInventory,
                    remainder.copyWithCount(StepExecutor.mulCount(
                            remainder.getCount(), remainderExecutions)));
        }
        return true;
    }

    //  multi-block step execution

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

    static List<BoundMachine> filterUnleasedMachines(List<BoundMachine> machines,
                                                      MachineLeaseRegistry leases,
                                                      String logicalType) {
        List<BoundMachine> available = new ArrayList<>();
        for (BoundMachine machine : machines) {
            MachineLeaseRegistry.MachineKey key = new MachineLeaseRegistry.MachineKey(
                    machine.dim(), machine.pos(), logicalType);
            if (!leases.isLeased(key)) available.add(machine);
        }
        return available;
    }

    enum MachineLeaseAvailability {
        NONE_BOUND,
        ALL_LEASED,
        AVAILABLE
    }

    static MachineLeaseAvailability classifyLeaseAvailability(int boundCount, int availableCount) {
        if (boundCount <= 0) return MachineLeaseAvailability.NONE_BOUND;
        if (availableCount <= 0) return MachineLeaseAvailability.ALL_LEASED;
        return MachineLeaseAvailability.AVAILABLE;
    }

    private IBatchDelegate startModStep(CraftingResolver.ResolutionStep step, ServerPlayer online) {
        machineStartFailureMessage = null;
        if (step.modType().isVirtual()) {
            IBatchDelegate virtualDelegate = createStepDelegate(step);
            if (virtualDelegate == null || !validatePreparedDelegate(
                    virtualDelegate, online, step.recipeId(), null, BlockPos.ZERO)) {
                return null;
            }
            try {
                if (virtualDelegate instanceof AbstractBatchDelegate abd) {
                    abd.setMachineServer(server);
                }
            } catch (RuntimeException exception) {
                releasePreparationQuietly(virtualDelegate);
                RSIntegrationMod.LOGGER.error(ctx.format(
                        "Virtual delegate configuration failed for {}"), step.recipeId(), exception);
                return null;
            }
            return startGenericStep(virtualDelegate, step, online);
        }
        // Extract machine sub-type from recipe ID (e.g. "wissen_crystallizer"
        // from "wizards_reborn:wissen_crystallizer/earth_crystal_seed") so we
        // only probe machines of the correct type, not every binding for the mod.
        List<BoundMachine> machines = AltarBindingRegistry.getBoundMachinesForRecipe(
                online, step.modType(), step.recipeId());
        if (machines.isEmpty()) {
            waitingForMachineLease = false;
            // Diagnostic: also check how many bindings exist for this mod type
            // (without sub-type filter) so we can tell if sub-type mismatch or
            // no binding at all.
            int totalForMod = AltarBindingRegistry.getBoundMachinesForType(
                    online, step.modType()).size();
            RSIntegrationMod.LOGGER.warn(ctx.format("No bound machine for mod type {} subType={} (total {} bindings for this mod)"),
                    step.modType(), AltarBindingRegistry.recipeSubTypeHint(step.recipeId()), totalForMod);
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
        int boundMachineCount = machines.size();
        machines = filterUnleasedMachines(machines, machineLeases, step.modType().id());
        if (classifyLeaseAvailability(boundMachineCount, machines.size())
                == MachineLeaseAvailability.ALL_LEASED) {
            if (!waitingForMachineLease) {
                RSIntegrationMod.LOGGER.debug(ctx.format(
                        "All bound machines for {} are leased by active operations; waiting to retry"),
                        step.modType());
            }
            waitingForMachineLease = true;
            return null;
        }
        waitingForMachineLease = false;

        //  Same-dimension priority
        // Prefer machines in the player's current dimension so cross-dimension
        // crafts only fall back to remote dimensions when no local machine exists.
        ResourceLocation playerDim = online.level().dimension().location();
        machines.sort((a, b) -> {
            boolean aSame = a.dim().equals(playerDim);
            boolean bSame = b.dim().equals(playerDim);
            if (aSame == bSame) return 0;
            return aSame ? -1 : 1;
        });
        machines = applyMachineSelection(machines, step.recipeId());

        //  Load-balanced multi-machine dispatch
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
        } else if (stepRemaining > configuredOperationsPerDispatch()) {
            // A ParallelCraftGroup reserves one material/token slice per queued
            // operation. Keep large orders on the bounded single-worker path;
            // otherwise one tick materializes every operation before any machine
            // begins processing it.
            RSIntegrationMod.LOGGER.debug(ctx.format(
                    "[LB] skipped: {} remaining operations exceed dispatch limit {}"),
                    stepRemaining, configuredOperationsPerDispatch());
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
                RSIntegrationMod.LOGGER.warn(ctx.format("[LB] parallel failed after commit -aborting instead of single-machine fallback"));
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

        IBatchDelegate initialDelegate = createStepDelegate(step);
        if (initialDelegate == null) return null;

        // GenericBatchDelegate computes the result from pre-reserved materials
        // without a physical machine.  Use the shared-ledger preReserve flow so
        // intermediate outputs from prior chain steps (in virtualInventory) are
        // visible to subsequent steps.
        if (initialDelegate.getClass() == GenericBatchDelegate.class) {
            if (!validatePreparedDelegate(
                    initialDelegate, online, step.recipeId(), null, BlockPos.ZERO)) {
                return null;
            }
            try {
                if (initialDelegate instanceof AbstractBatchDelegate abd) {
                    abd.setMachineServer(server);
                }
            } catch (RuntimeException exception) {
                releasePreparationQuietly(initialDelegate);
                RSIntegrationMod.LOGGER.error(ctx.format(
                        "Generic delegate configuration failed for {}"), step.recipeId(), exception);
                return null;
            }
            return startGenericStep(initialDelegate, step, online);
        }

        MachineCandidateSelection candidateSelection = filterMachineCandidates(
                machines,
                machine -> {
                    ResourceKey<Level> dimKey = ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, machine.dim());
                    ServerLevel machineLevel = server.getLevel(dimKey);
                    return machineLevel != null && machineLevel.hasChunkAt(machine.pos());
                },
                machine -> {
                    ResourceKey<Level> dimKey = ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, machine.dim());
                    ServerLevel machineLevel = server.getLevel(dimKey);
                    return machineLevel != null
                            && ProtectionChecker.canInteract(online, machineLevel, machine.pos());
                });

        releasePreparationQuietly(initialDelegate);
        IBatchDelegate delegate = null;
        // Try each bound machine until one is ready. Retryable preparation never
        // reaches material reservation or ledger commit.
        BoundMachine matchedMachine = null;
        boolean retryableRejection = false;
        boolean unloadedRejection = candidateSelection.unloadedRejected();
        boolean protectionRejection = candidateSelection.protectionRejected();
        String fatalDetail = "";
        Component fatalUserMessage = null;
        for (BoundMachine m : candidateSelection.usable()) {
            IBatchDelegate candidate = null;
            boolean retained = false;
            try {
                candidate = createStepDelegate(step);
                if (candidate == null) continue;
                if (candidate instanceof AbstractBatchDelegate abd) {
                    // Preparation can inspect storage-backed availability. The
                    // legacy flat path used to attach the selected endpoint
                    // only after preparation, which made non-RS backends (BD)
                    // appear disconnected and left the step retrying forever.
                    abd.setStorageEndpoint(storageEndpoint);
                }
                IBatchDelegate.PreparationResult preparation = PreparationMessageScope.prepare(
                        candidate, online, step.recipeId(), m.dim(), m.pos());
                if (preparation.state() == IBatchDelegate.PreparationState.READY) {
                    if (candidate instanceof AbstractBatchDelegate abd) {
                        abd.setMachineDim(m.dim());
                        abd.setMachineServer(server);
                        applyTargetOutput(abd);
                    }
                    delegate = candidate;
                    matchedMachine = m;
                    retained = true;
                    break;
                }
                if (preparation.state() == IBatchDelegate.PreparationState.RETRY) {
                    retryableRejection = true;
                } else if (fatalDetail.isEmpty()) {
                    fatalDetail = preparation.detail();
                    fatalUserMessage = preparation.userMessage();
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug(ctx.format("prepare failed for machine at {}"), m.pos(), e);
                if (fatalDetail.isEmpty()) fatalDetail = e.getMessage();
            } finally {
                if (candidate != null && !retained) releasePreparationQuietly(candidate);
            }
        }
        if (matchedMachine == null) {
            if (retryableRejection) {
                waitingForMachineLease = true;
                RSIntegrationMod.LOGGER.debug(ctx.format(
                        "All {} unleased machines are temporarily unavailable for {}"),
                        machines.size(), step.recipeId());
                return null;
            }
            RSIntegrationMod.LOGGER.warn(ctx.format(
                    "All {} bound machines failed preparation for mod type {}: recipe={} detail={}"),
                    machines.size(), step.modType(), step.recipeId(),
                    fatalDetail);
            machineStartFailureMessage = fatalUserMessage != null
                    ? fatalUserMessage
                    : protectionRejection
                    ? Component.translatable("rsi.error.protection_denied")
                    : unloadedRejection
                    ? Component.translatable("rsi.error.chunk_unloaded")
                    : Component.translatable("rsi.async.error.machine_valid_failed", step.recipeId());
            return null;
        }

        final IBatchDelegate startedDelegate = delegate;
        try {
            int allowedBatch = Math.min(stepRemaining, configuredOperationsPerDispatch());
            int flatBatch = startedDelegate.prepareFlatBatch(allowedBatch);
            if (flatBatch <= 0 || flatBatch > allowedBatch) {
                RSIntegrationMod.LOGGER.warn(ctx.format(
                        "Delegate selected invalid flat batch size {} for {} permitted operation(s)"),
                        flatBatch, allowedBatch);
                releasePreparationQuietly(startedDelegate);
                return null;
            }
            machineCount = flatBatch;
            List<IngredientSpec> specs = startedDelegate.getRequiredMaterials();
            if (specs != null && !specs.isEmpty()) {
                if (startedDelegate instanceof AbstractBatchDelegate abstractDelegate) {
                    abstractDelegate.setStorageEndpoint(storageEndpoint);
                }
                startedDelegate.configureMaterialReservation(ledger, online);
                List<IngredientSpec> batchSpecs = scaleGraphSpecsForExecutions(
                        specs, startedDelegate.getMaterialReservationScopes(), flatBatch);
                List<ItemStack> materials = preReserveStepMaterials(batchSpecs, online);
                if (materials == null) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("Failed to pre-reserve materials for {}"),
                            step.recipeId());
                    Component specific = startedDelegate.materialReservationFailureMessage(online);
                    online.sendSystemMessage(specific != null ? specific : Component.translatable(
                            "rsi.generic.error.missing_materials", step.recipeId()));
                    try { delegate.onBatchFailed(online, "pre-reserve failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during pre-reserve cleanup"), fe);
}
                    return null;
                }
                if (!acquireFlatOperationScope(delegate, matchedMachine, step)) {
                    RSIntegrationMod.LOGGER.debug(ctx.format("Physical operation resources busy for {}"),
                            step.recipeId());
                    try { delegate.onBatchFailed(online, "operation resources busy"); } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during resource cleanup"), fe);
                    }
                    restoreVirtualFromCommitted();
                    if (ledger.state() != ExtractionLedger.State.IDLE) ledger.reset();
                    waitingForMachineLease = true;
                    return null;
                }
                if (!flatOperationSession.commit(() -> commitLedger(ledger, online))) {
                    closeFlatOperationScope();
                    RSIntegrationMod.LOGGER.warn(ctx.format("Ledger commit failed for {}"),
                            step.recipeId());
                    Component specific = startedDelegate.materialReservationFailureMessage(online);
                    online.sendSystemMessage(specific != null ? specific : Component.translatable(
                            "rsi.generic.error.missing_materials", step.recipeId()));
                    try { delegate.onBatchFailed(online, "commit failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during commit cleanup"), fe);
}
                    return null;
                }
                if (delegate instanceof AbstractBatchDelegate abd) {
                    abd.setStorageEndpoint(storageEndpoint);
                    abd.useSharedLedger(ledger);
                }
                if (!flatOperationSession.tryStart(
                        () -> startedDelegate.tryStartWithMaterials(online, materials, ledger))) {
                    List<ItemStack> escaped = disarmOutputCapture();
                    closeFlatOperationScope();
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
                // Some machines (notably CrockPot's birdcage) determine their
                // output only while starting. Arm a second capture after that
                // point so its delayed world drop cannot be picked up first.
                armOutputCapture(startedDelegate, online);
            } else {
                // Private-ledger path: tryStartSingleCraft's ensureMaterialAvailable
                // only sees network + player inventory, NOT virtualInventory. In a
                // multi-step chain, intermediate products from earlier steps live in
                // virtualInventory and would be invisible here -the craft fails even
                // though the material exists (e.g. CrockPot category filler / WR
                // arcane iterator as a mid-chain step: works alone, fails as a
                // dependency). Flush them into the network first so this step can
                // consume them, then re-snapshot committedVirtual to empty: the
                // products are now network-owned, so recoverCommittedVirtual must NOT
                // re-deliver them on abort (that would duplicate). The ledger refund
                // on abort returns whatever this step consumed back to the network.
                if (storageEndpoint != null && !virtualInventory.isEmpty()) {
                    flushVirtualInventory(online);
                    snapshotCommittedVirtual(); // virtualInventory now empty ->empty snapshot
                }
                if (!acquireFlatOperationScope(delegate, matchedMachine, step)) {
                    RSIntegrationMod.LOGGER.debug(ctx.format("Physical operation resources busy for {}"),
                            step.recipeId());
                    try { delegate.onBatchFailed(online, "operation resources busy"); } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during resource cleanup"), fe);
                    }
                    waitingForMachineLease = true;
                    return null;
                }
                if (!flatOperationSession.commit(() -> {
                    if (!ledger.isCommitted()) return commitLedger(ledger, online);
                    return true;
                })) {
                    closeFlatOperationScope();
                    releasePreparationQuietly(startedDelegate);
                    return null;
                }
                if (!flatOperationSession.tryStart(
                        () -> startedDelegate.tryStartSingleCraft(online, ledger))) {
                    List<ItemStack> escaped = disarmOutputCapture();
                    closeFlatOperationScope();
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
                armOutputCapture(startedDelegate, online);
            }
        } catch (Exception e) {
            List<ItemStack> escaped = disarmOutputCapture();
            closeFlatOperationScope();
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

    //  generic (no-machine) delegate: shared-ledger pre-reserve flow

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
                if (!delegate.validateExecutionContext(online)) {
                    RSIntegrationMod.LOGGER.warn(ctx.format(
                            "Execution context unavailable for generic step {}"), step.recipeId());
                    try { delegate.onBatchFailed(online, "execution context unavailable"); }
                    catch (Exception ignored) { }
                    return null;
                }
                if (!commitLedger(ledger, online)) {
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
                    abd.setStorageEndpoint(storageEndpoint);
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
                    return null;
                }
            } else {
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

    record MachineCandidateSelection(List<BoundMachine> usable,
                                     boolean unloadedRejected,
                                     boolean protectionRejected) {}

    static MachineCandidateSelection filterMachineCandidates(
            List<BoundMachine> machines,
            Predicate<BoundMachine> loaded,
            Predicate<BoundMachine> permitted) {
        List<BoundMachine> usable = new ArrayList<>();
        boolean unloadedRejected = false;
        boolean protectionRejected = false;
        for (BoundMachine machine : machines) {
            if (!loaded.test(machine)) {
                unloadedRejected = true;
                continue;
            }
            if (!permitted.test(machine)) {
                protectionRejected = true;
                continue;
            }
            usable.add(machine);
        }
        return new MachineCandidateSelection(
                List.copyOf(usable), unloadedRejected, protectionRejected);
    }

    //  parallel (load-balanced) step

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
            RSIntegrationMod.LOGGER.debug(ctx.format("[LB] filterAvailable: {} machines ->{} available (<2, abort)"),
                    machines.size(), available.size());
            return null;
        }
        RSIntegrationMod.LOGGER.debug(ctx.format("[LB] filterAvailable: {} machines ->{} available"),
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

        // Build a parallel group -constructor internally creates and validates
        // one delegate per machine. Pass the same recipe-aware capability contract
        // used by graph execution; otherwise the operation kernel accepts the group
        // but rejects every child as capability-exclusive after materials are committed.
        IBatchDelegate capabilityProbe = createStepDelegate(step);
        var capabilityDecision = concurrencyDecision(step, capabilityProbe);
        if (capabilityDecision.exclusive()) {
            RSIntegrationMod.LOGGER.debug(ctx.format(
                    "[LB] parallel disabled by capability policy: {}"), capabilityDecision.reason());
            return null;
        }
        ParallelCraftGroup group = new ParallelCraftGroup(available, step.modType(),
                step.recipeId(), online, stepRemaining, step.inferMode(),
                capabilityDecision.capabilities());
        if (!validatePreparedDelegate(
                group, online, step.recipeId(), null, BlockPos.ZERO)) {
            RSIntegrationMod.LOGGER.debug(ctx.format("Parallel group empty -all children failed validateAndInit"));
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
                    ? parallel.getFlatOperationMaterials() : null;
            List<IngredientSpec> specs = operationSpecs != null ? operationSpecs : group.getRequiredMaterials();
            if (specs != null && !specs.isEmpty()) {
                List<ItemStack> materials;
                if (operationSpecs != null && !operationSpecs.isEmpty()
                        && group instanceof ParallelCraftGroup parallel) {
                    ParallelReservation reserved = preReserveParallelOperations(
                            operationSpecs, parallel.getMaterialReservationScopes(),
                            parallel.getChildCount(), stepRemaining, online);
                    if (reserved == null) {
                        materials = null;
                    } else {
                        materials = new ArrayList<>();
                        List<ExtractionLedger.ReservationToken> tokens = new ArrayList<>();
                        List<List<ItemStack>> virtualDebits = new ArrayList<>();
                        for (ReservedOperation operation : reserved.operations()) {
                            materials.addAll(copyStacksKeepingEmpty(operation.materials()));
                            tokens.add(operation.token());
                            virtualDebits.add(copyStacks(operation.virtualDebits()));
                        }
                        parallel.setReservationTokens(tokens);
                        parallel.setReusableReservationTokens(reserved.reusableTokens());
                        parallel.setVirtualDebits(virtualDebits);
                        parallel.setOperationKernel(operationKernel, craftId,
                                new NodeId(Math.max(0, currentStepIdx)), craftOperationBudget);
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
                if (!commitLedger(ledger, online)) {
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

    private record ParallelReservation(List<ReservedOperation> operations,
                                       List<ExtractionLedger.ReservationToken> reusableTokens) {}

    private ParallelReservation preReserveParallelOperations(
            List<IngredientSpec> specs,
            List<IBatchDelegate.MaterialReservationScope> scopes,
            int workerCount, int operationCount, ServerPlayer online) {
        List<ItemStack> virtualSnapshot = copyStacks(virtualInventory);
        List<ReservedOperation> reservations = new ArrayList<>();
        int effectiveWorkers = Math.min(Math.max(1, workerCount), operationCount);
        List<ItemStack> reusable = new ArrayList<>();
        List<List<Integer>> reusableEntryIds = new ArrayList<>();
        for (int worker = 0; worker < effectiveWorkers; worker++) {
            reusableEntryIds.add(new ArrayList<>());
        }
        List<Integer> reusableIndices = reusableMaterialIndices(scopes, specs.size());
        for (int i : reusableIndices) {
            IngredientSpec spec = specs.get(i);
            for (int worker = 0; worker < effectiveWorkers; worker++) {
                List<IngredientSpec> one = List.of(new IngredientSpec(
                        spec.ingredient(), spec.count(), spec.role()));
                int reusableMark = ledger.reservationMark();
                List<ItemStack> material = preReserveStepMaterials(one, online);
                if (material == null) {
                    ledger.reset();
                    restoreVirtualSnapshot(virtualSnapshot);
                    return null;
                }
                reusable.addAll(material);
                reusableEntryIds.get(worker).addAll(ledger.tokenSince(reusableMark).entryIds());
            }
        }
        for (int operation = 0; operation < operationCount; operation++) {
            int mark = ledger.reservationMark();
            List<ItemStack> virtualDebits = new ArrayList<>();
            List<IngredientSpec> perOperation = new ArrayList<>();
            for (int i = 0; i < specs.size(); i++) {
                if (!reusableIndices.contains(i)) {
                    perOperation.add(specs.get(i));
                }
            }
            List<ItemStack> materials = preReserveStepMaterials(perOperation, online, virtualDebits);
            if (materials == null) {
                ledger.reset();
                restoreVirtualSnapshot(virtualSnapshot);
                return null;
            }
            List<ItemStack> full = new ArrayList<>();
            int perIndex = 0;
            int reusableIndex = 0;
            for (int i = 0; i < specs.size(); i++) {
                if (reusableIndices.contains(i)) {
                    full.add(reusable.get((operation % effectiveWorkers) + reusableIndex));
                    reusableIndex += effectiveWorkers;
                } else {
                    full.add(materials.get(perIndex++));
                }
            }
            reservations.add(new ReservedOperation(full, ledger.tokenSince(mark), List.copyOf(virtualDebits)));
        }
        List<ExtractionLedger.ReservationToken> reusableTokens = reusableEntryIds.stream()
                .map(ExtractionLedger.ReservationToken::new)
                .toList();
        return new ParallelReservation(List.copyOf(reservations), reusableTokens);
    }

    private ParallelReservation preReserveParallelOperations(
            List<IngredientSpec> specs, int operationCount, ServerPlayer online) {
        return preReserveParallelOperations(specs, List.of(), 1, operationCount, online);
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

            if (needed > 0 && storageEndpoint != null) {
                ItemStack reserved = ledger.reserveFromEndpoint(spec.ingredient(), needed, storageEndpoint, online);
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

    /** Reserve materials deliberately omitted from graph demands. */
    @Nullable
    private List<ItemStack> reserveSupplementalMaterials(
            List<IngredientSpec> specs, ServerPlayer online, ExtractionLedger targetLedger) {
        int mark = targetLedger.reservationMark();
        List<ItemStack> materials = new ArrayList<>(specs.size());
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) {
                materials.add(ItemStack.EMPTY);
                continue;
            }
            ItemStack reserved = storageEndpoint != null
                    ? targetLedger.reserve(spec.ingredient(), spec.count(), storageEndpoint, online, null, null)
                    : targetLedger.reserve(spec.ingredient(), spec.count(), network, online, null, null);
            if (reserved.isEmpty() || reserved.getCount() != spec.count()) {
                while (targetLedger.reservationMark() > mark) {
                    targetLedger.cancelLastReservation();
                }
                RSIntegrationMod.LOGGER.warn(ctx.format(
                                "Supplemental reserve failed: need {} of '{}' for step {}"),
                        spec.count(), CraftPacketUtils.describeIngredient(spec.ingredient()).getString(),
                        steps.get(currentStepIdx).recipeId());
                return null;
            }
            materials.add(reserved);
        }
        return materials;
    }

    //  output capture (magnet protection)

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

        if (captureHandle == null) {
            this.captureHandle = CraftOutputInterceptor.arm(dim, region, expected,
                    delegate.allowsOverlappingOutputCaptureOrigins());
        }
    }

    private boolean hasCapturedOutput() {
        return (flatOperationSession != null && flatOperationSession.hasCaptured())
                || (captureHandle != null && captureHandle.hasCaptured());
    }

    private List<ItemStack> capturedOutputSnapshot() {
        List<ItemStack> captured = new ArrayList<>();
        if (flatOperationSession != null) captured.addAll(flatOperationSession.capturedSnapshot());
        if (captureHandle != null) captured.addAll(captureHandle.snapshot());
        return List.copyOf(captured);
    }

    private boolean hasCapturedExpectedCount(ItemStack expected) {
        if (expected == null || expected.isEmpty()) return false;
        int captured = capturedOutputSnapshot().stream()
                .filter(stack -> ItemStack.isSameItem(stack, expected))
                .mapToInt(ItemStack::getCount)
                .sum();
        return captured >= expected.getCount();
    }

    private boolean acquireFlatOperationScope(IBatchDelegate delegate, BoundMachine machine,
                                              CraftingResolver.ResolutionStep step) {
        if (flatOperationSession != null) return false;
        ItemStack expected = delegate.getExpectedOutput();
        AABB region = delegate.getOutputCaptureRegion();
        OperationResourceCoordinator.CaptureRequest capture = expected != null && !expected.isEmpty()
                && region != null
                ? new OperationResourceCoordinator.CaptureRequest(machine.dim(), region, expected,
                        delegate.allowsOverlappingOutputCaptureOrigins())
                : null;
        BlockPos operationMachinePos = delegate.getOperationMachinePos(machine.pos());
        MachineLeaseRegistry.MachineKey key = new MachineLeaseRegistry.MachineKey(
                machine.dim(), operationMachinePos, step.modType().id());
        flatOperationSession = operationKernel.tryPrepare(craftId,
                new NodeId(Math.max(0, currentStepIdx)), 0, craftOperationBudget, key, capture);
        return flatOperationSession != null;
    }

    private void closeFlatOperationScope() {
        OperationExecutionKernel.Session session = flatOperationSession;
        flatOperationSession = null;
        if (session != null) session.close();
    }

    /** Tear down the active capture zone and return whatever it grabbed. */
    private List<ItemStack> disarmOutputCapture() {
        List<ItemStack> captured = new ArrayList<>();
        OperationExecutionKernel.Session session = flatOperationSession;
        if (session != null) captured.addAll(session.drainCapture());
        CraftOutputInterceptor.CaptureHandle handle = captureHandle;
        captureHandle = null;
        if (handle != null) captured.addAll(handle.drainAndClose());
        return List.copyOf(captured);
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
                    && IBatchDelegate.matchesProducedItem(stack, expected.item())) {
                count = count > Integer.MAX_VALUE - stack.getCount()
                        ? Integer.MAX_VALUE : count + stack.getCount();
            }
        }
        return count;
    }

    //  virtual inventory

    private void addToVirtualInventory(ItemStack stack) {
        addToInventory(virtualInventory, stack);
    }

    private static void addToInventory(List<ItemStack> inventory, ItemStack stack) {
        if (stack.isEmpty()) return;
        for (ItemStack existing : inventory) {
            if (ItemStack.isSameItemSameTags(existing, stack)) {
                existing.grow(stack.getCount());
                return;
            }
        }
        inventory.add(stack.copy());
    }

    /**
     * Restore {@link #virtualInventory} to the last settled baseline WITHOUT
     * flushing -used to undo a failed attempt (e.g. parallel dispatch that
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
     * the network, given to the player, or dropped at spawn -never silently lost.
     * Runs after the ledger refund and only once state is already ABORTED, so it
     * cannot re-enter abort() other than via the drop-throttle guard.
     */
    private void recoverCommittedVirtual(@Nullable ServerPlayer online) {
        virtualInventory.clear();
        for (ItemStack owed : committedVirtual) {
            if (owed.isEmpty()) continue;
            if (online != null) {
                ItemStack leftover = owed.copy();
                leftover = insertIntoStorage(online, owed);
                if (!leftover.isEmpty()) safeGiveToPlayer(online, leftover);
            } else if (!insertOrDropAtSpawn(owed.copy())) {
                // Drop throttle tripped (network full/absent, player offline,
                // >20 drops this chain). insertOrDropAtSpawn already logged the
                // discard. Do NOT clear the rest silently -surface it as CRITICAL
                // so the docstring's "never silently lost" promise isn't a lie.
                RSIntegrationMod.LOGGER.error(ctx.format(
                        "CRITICAL: drop throttle tripped during committed-product recovery; "
                        + "remaining owed products cannot be delivered for player {}"), playerId);
            }
        }
        committedVirtual.clear();
    }

    private void flushVirtualInventory(ServerPlayer online) {
        if (storageEndpoint == null) return;
        var iter = virtualInventory.iterator();
        while (iter.hasNext()) {
            ItemStack vi = iter.next();
            if (!vi.isEmpty()) {
                ItemStack leftover = insertIntoStorage(online, vi);
                if (online != null && !leftover.isEmpty()) {
                    safeGiveToPlayer(online, leftover);
                } else if (online == null && !leftover.isEmpty()) {
                    if (!insertOrDropAtSpawn(leftover)) {
                        // Throttle tripped -stop flushing and abort so
                        // remaining items are not silently destroyed.
                        // Leave unconsumed items in virtualInventory for
                        // the abort path to refund.
                        abort("Drop throttle tripped -RS network full and player offline",
                                Component.translatable("rsi.async.abort.drop_throttle"));
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
     *         (throttle tripped -further drops would be silently discarded)
     */
    private boolean insertOrDropAtSpawn(ItemStack stack) {
        if (storageEndpoint != null) {
            ItemStack stillLeft = insertIntoStorage(resolvePlayer(), stack);
            if (stillLeft.isEmpty()) return true;
            stack = stillLeft;
        }
        if (dropThrottleTripped) {
            RSIntegrationMod.LOGGER.warn("[RSI] Drop throttle tripped -discarding {} x{} for player {}",
                    com.huanghuang.rsintegration.util.ItemStackUtils.registryId(stack), stack.getCount(), playerId);
            return false;
        }
        dropsThisChain++;
        if (dropsThisChain > MAX_DROPS_PER_CHAIN) {
            dropThrottleTripped = true;
            RSIntegrationMod.LOGGER.warn("[RSI] Drop throttle tripped ({} drops) -discarding {} x{} and all future drops for player {}. Chain will abort.",
                    MAX_DROPS_PER_CHAIN, com.huanghuang.rsintegration.util.ItemStackUtils.registryId(stack), stack.getCount(), playerId);
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
                playerId, com.huanghuang.rsintegration.util.ItemStackUtils.registryId(stack), stack.getCount());
        }
        return true;
    }

    //  lifecycle

    private boolean finish(ServerPlayer online) {
        if (!finalSettlementPrepared && useGraphExecution && graphMaterials != null) {
            for (ItemStack stack : graphMaterials.drainAvailableProducerAssets()) {
                addToVirtualInventory(stack);
            }
        }
        if (!finalSettlementPrepared && !commitLedger(ledger, online)) {
            RSIntegrationMod.LOGGER.warn(ctx.format("Commit failed for player {} after {} steps"),
                    online.getName().getString(), steps.size());
            online.sendSystemMessage(Component.translatable("rsi.async.error.commit_failed"));
            abort("Final commit failed",
                    Component.translatable("rsi.async.abort.final_commit_failed"));
            return true;
        }
        finalSettlementPrepared = true;

        int stackBudget = finalSettlementStacksPerTick();
        while (finalSettlementCursor < virtualInventory.size() && stackBudget-- > 0) {
            ItemStack vi = virtualInventory.get(finalSettlementCursor++);
            if (!vi.isEmpty()) {
                if (storageEndpoint != null) {
                    boolean playerOutput = outputDestination == OutputDestination.PLAYER_INVENTORY
                            && matchesFinalTarget(vi);
                    ItemStack leftover = playerOutput ? insertIntoPlayerInventory(online, vi) : vi.copy();
                    ItemStack rsCandidate = leftover.copy();
                    if (!leftover.isEmpty()) {
                        leftover = insertIntoStorage(online, rsCandidate);
                    }
                    ItemStack inserted = InsertedStackDelta.between(vi, leftover);
                    if (targetOutput != null && vi.is(targetOutput.getItem())) {
                        ExternalItemProgressBridge.enqueueCrafted(
                                online, inserted);
                    }
                    if (!leftover.isEmpty()) {
                        safeGiveToPlayer(online, leftover);
                    }
                } else {
                    // Keep the standalone path identical to the RS player's
                    // inventory destination: insert first, then deliver only
                    // a genuine remainder.  ItemHandlerHelper can trigger
                    // equipment/curio handlers for wearable outputs; handing
                    // the full stack to it after insertion leaves a client-side
                    // duplicate until the equipped slot is clicked.
                    ItemStack leftover = insertIntoPlayerInventory(online, vi.copy());
                    if (!leftover.isEmpty()) safeGiveToPlayer(online, leftover);
                }
            }
        }

        if (finalSettlementCursor < virtualInventory.size()) {
            maybeSendProgress(online, false);
            return false;
        }

        state = State.COMPLETED;
        Diagnostics.record(Diagnostics.Category.CHAIN_STATE, "->OMPLETED steps=" + steps.size());
        RSIntegrationMod.LOGGER.info(ctx.format("COMPLETED for player {}: {} steps"),
                online.getName().getString(), steps.size());
        sendTerminalProgress(online);
        fireOnDone();
        return true;
    }

    private static int finalSettlementStacksPerTick() {
        try {
            return Math.max(1, RSIntegrationConfig.CRAFTING_SETTLEMENT_STACKS_PER_TICK.get());
        } catch (Exception ignored) {
            return RSIntegrationConfig.DEFAULT_CRAFTING_SETTLEMENT_STACKS_PER_TICK;
        }
    }

    private boolean matchesFinalTarget(ItemStack stack) {
        return matchesFinalTarget(stack, graph, graphDeclaresFinalOutput, targetOutput);
    }

    static boolean matchesFinalTarget(ItemStack stack, @Nullable CraftPlanGraph graph,
                                      boolean graphDeclaresFinalOutput,
                                      @Nullable ItemStack targetOutput) {
        if (stack.isEmpty()) return false;
        // A complete graph remains the authoritative output declaration even
        // when its adapters require legacy flat execution. Restrict this to
        // self-contained graphs: compatibility graphs describe only terminal
        // inputs, so their roots are intermediate materials rather than the
        // requested product.
        if (graphDeclaresFinalOutput && graph != null) {
            return matchesGraphFinalOutput(graph, stack);
        }
        if (targetOutput == null || targetOutput.isEmpty()) return false;
        return targetOutput.hasTag()
                ? ItemStack.isSameItemSameTags(stack, targetOutput)
                : ItemStack.isSameItem(stack, targetOutput);
    }

    static boolean matchesGraphFinalOutput(CraftPlanGraph graph, ItemStack stack) {
        if (graph == null || stack == null || stack.isEmpty()) return false;
        return graph.rootDemands().stream()
                .flatMap(root -> root.allocations().stream())
                .filter(allocation -> allocation.source() instanceof MaterialSource.ProducerOutput)
                .anyMatch(allocation -> MaterialMatcher.matchesOutputDeclaration(
                        allocation.material(), stack));
    }

    private ItemStack insertIntoPlayerInventory(ServerPlayer player, ItemStack stack) {
        return PlayerUtils.insertIntoPlayerInventory(player, stack);
    }

    /**
     * Backend-neutral storage helpers. The legacy INetwork field remains only
     * for native RS compatibility; all migrated execution paths use the
     * resolved endpoint and its session operations.
     */
    private boolean commitLedger(ExtractionLedger target, @Nullable ServerPlayer player) {
        if (player == null) return false;
        if (storageEndpoint != null) target.setStorageEndpoint(storageEndpoint);
        return target.commit(network, player);
    }

    private void refundCommitted(ExtractionLedger target, @Nullable ServerPlayer player) {
        if (storageEndpoint != null) target.setStorageEndpoint(storageEndpoint);
        target.refundCommitted(network, player);
    }

    private ItemStack reserveIngredient(ExtractionLedger target, Ingredient ingredient,
                                        int amount, ServerPlayer player) {
        if (amount <= 0 || ingredient.isEmpty()) return ItemStack.EMPTY;
        if (storageEndpoint != null) {
            ItemStack stored = target.reserveFromEndpoint(ingredient, amount, storageEndpoint, player);
            if (!stored.isEmpty()) return stored;
        } else if (network != null) {
            ItemStack stored = target.reserveFromNetwork(ingredient, amount, network, player);
            if (!stored.isEmpty()) return stored;
        }
        return target.reserveFromInventory(ingredient, amount, player);
    }

    private ItemStack reserveExact(ExtractionLedger target, ItemStack template,
                                   int amount, ServerPlayer player) {
        if (storageEndpoint != null) {
            return target.reserveExactAcrossNetworkAndInventory(template, amount, storageEndpoint, player);
        }
        return target.reserveExactAcrossNetworkAndInventory(template, amount, network, player);
    }

    private ItemStack insertIntoStorage(@Nullable ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        if (storageEndpoint == null || player == null) return stack.copy();
        return storageEndpoint.insert(player, stack, false).remainder().orElse(ItemStack.EMPTY);
    }

    private void fireOnDone() {
        terminalListeners.fireOnce();
    }

    /** Register an additive completion listener. Late listeners are queued on the server tick. */
    public void onDone(@Nullable Runnable callback) {
        terminalListeners.add(callback);
    }

    /** How many machines produced output this chain run (1 for single machine, N for parallel). */
    public int getMachineCount() {
        return machineCount;
    }

    /** Abort after a physical machine consumed inputs but its output escaped.
     * Refunding here would duplicate the escaped result. */
    private void abortWithoutRefund(String reason, Component userReason) {
        terminate(reason, userReason, TerminationService.Policy.NO_REFUND,
                TerminationCoordinator.Cause.FAILURE);
    }

    /**
     * Aborts and refunds. {@code reason} is the log line (English is fine);
     * {@code userReason} is shown to the player and must be a translatable
     * Component — the server cannot resolve translations, so a pre-rendered or
     * literal English string would reach the client untranslated.
     */
    public void abort(String reason, Component userReason) {
        terminate(reason, userReason, TerminationService.Policy.REFUND_AND_DELIVER,
                TerminationCoordinator.Cause.FAILURE);
    }

    /** @param userReason player-facing text; must be translatable, not literal English. */
    public void cancel(String reason, Component userReason) {
        terminate(reason, userReason, TerminationService.Policy.REFUND_AND_DELIVER,
                TerminationCoordinator.Cause.CANCELLED);
    }

    /**
     * Player is already offline: refund silently. {@code userReason} is unused
     * under {@link SettlementPolicy#SILENT_REFUND} (no chat is sent), so a
     * literal log string is safe here.
     */
    public void abortOffline(String reason) {
        terminate(reason, Component.literal(reason), TerminationService.Policy.SILENT_REFUND,
                TerminationCoordinator.Cause.OFFLINE);
    }

    public void abortForServerStop() {
        if (graphExecutor != null) {
            try {
                graphExecutor.quiesceOnce();
            } catch (RuntimeException exception) {
                RSIntegrationMod.LOGGER.error(ctx.format("Server-stop quiesce failed"), exception);
            }
        } else if (currentDelegate != null) {
            try {
                IBatchDelegate.CraftObservation observation = currentDelegate.observeCraft(server.overworld());
                // World-output capture cancels the spawned ItemEntity before the delegate can observe it.
                // Treat a matching captured output as completion so it settles into the RS inventory.
                boolean capturedWorldOutput = hasCapturedExpectedCount(currentDelegate.getExpectedOutput());
                if (observation.phase() == IBatchDelegate.CraftPhase.DONE || capturedWorldOutput) {
                    collectCompletedDelegateForShutdown(resolvePlayer());
                }
            } catch (RuntimeException exception) {
                RSIntegrationMod.LOGGER.error(ctx.format("Flat server-stop observation failed"), exception);
            }
        }
        terminate("Server stopping", Component.literal("Server stopping"),
                TerminationService.Policy.SILENT_REFUND, TerminationCoordinator.Cause.SERVER_STOP);
    }

    /**
     * Settle a craft that already finished when the server began stopping.
     *
     * <p>Must run even when the owning player is offline. Bailing out on a null
     * player used to leave the delegate un-settled, so {@link #terminate} then
     * treated a <em>completed</em> craft as a failure: the ledger refunded the
     * consumed inputs while the finished product stayed in the machine — the
     * same material on both sides of the restart. The product is collected into
     * the virtual inventory here and delivered by the normal recovery path,
     * which already handles a null player.</p>
     */
    private void collectCompletedDelegateForShutdown(@Nullable ServerPlayer player) {
        if (currentDelegate == null) return;
        if (player != null) {
            for (ItemStack result : currentDelegate.collectAllResults(player)) {
                if (result != null && !result.isEmpty()) addToVirtualInventory(result);
            }
        } else {
            // No player to fall back to, so take the captured world output
            // directly; recoverCommittedVirtual handles delivery without one.
            for (ItemStack captured : disarmOutputCapture()) {
                if (captured != null && !captured.isEmpty()) addToVirtualInventory(captured);
            }
            RSIntegrationMod.LOGGER.warn(ctx.format(
                    "Server stop: owner offline for a completed craft; settling it so inputs are not refunded twice"));
        }
        currentDelegate.onBatchFinished(player);
        currentDelegate.releaseReusableMaterials(player);
        if (flatOperationSession != null && flatOperationSession.startAttempted()
                && !flatOperationSession.settled()) {
            flatOperationSession.settle(() -> {
                if (ledger.isCommitted()) ledger.settleAllCommitted();
            });
        }
        snapshotCommittedVirtual();
        currentDelegate = null;
    }

    private void abortSilently(String reason) {
        abortOffline(reason);
    }

    /**
     * Unified terminal-abort skeleton. All three former {@code abort*} variants
     * funnel through here; {@code policy} selects the refund/delivery/silence
     * behaviour. Idempotent: a chain already in a terminal state is a no-op.
     *
     * <p>Fixed order (identical to the pre-merge variants): delegate cleanup ->
     * captured outputs ->graph-node cleanup ->ledger refund/rollback ->recover
     * earlier settled products ->close ledger ->notify ->fire terminal listeners.
     * {@code committedVirtual} is disjoint from both the ledger (this step's
     * reserved inputs) and the captured outputs (this step's world drop), so
     * recovery never duplicates; in-flight products from rolled-back
     * reservations are discarded.
     */
    private void terminate(String reason, Component userReason, TerminationService.Policy policy,
                           TerminationCoordinator.Cause cause) {
        if (state == State.ABORTED || state == State.COMPLETED) return;

        ServerPlayer online = policy.silent() ? null : resolvePlayer();
        RSIntegrationMod.LOGGER.warn(ctx.format("Aborting chain (state={}, policy={}) for {}: {}"),
                state, policy, online != null ? online.getName().getString() : playerId, reason);
        Diagnostics.record(Diagnostics.Category.CHAIN_STATE,
                "->BORTED policy=" + policy + " reason=" + reason + " atStep=" + currentStepIdx + "/" + steps.size());
        state = State.ABORTED;
        terminalCause = cause;
        abortReason = reason;

        // Delegate cleanup - works even when player is offline.
        boolean flatPhysicalCleanupRequired = flatOperationSession != null
                && flatOperationSession.machineLease() != null;
        boolean flatFailureRefundSafe = !flatPhysicalCleanupRequired;
        List<ItemStack> flatFailureRecoveredInputs = null;
        if (currentDelegate != null) {
            try {
                currentDelegate.onBatchFailed(online, reason);
                flatFailureRecoveredInputs = currentDelegate.failureRecoveredInputs();
                if (flatPhysicalCleanupRequired
                        && currentDelegate instanceof AbstractBatchDelegate delegate) {
                    flatFailureRefundSafe = delegate.physicalFailureCleanupCompleted();
                } else if (flatPhysicalCleanupRequired
                        && currentDelegate instanceof ParallelCraftGroup group) {
                    flatFailureRefundSafe = group.physicalFailureCleanupCompleted();
                }
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
        OperationExecutionKernel.TerminalClass flatTerminalClass = flatOperationSession == null
                ? null : flatOperationSession.terminalClass();
        if (flatTerminalClass == OperationExecutionKernel.TerminalClass.IN_FLIGHT
                && flatFailureRecoveredInputs != null && ledger.isCommitted()) {
            ledger.retainCommittedRefunds(flatFailureRecoveredInputs);
            flatFailureRefundSafe = true;
        }
        boolean refundFlatCommitted = shouldRefundFlatCommitted(
                flatTerminalClass, flatFailureRefundSafe);

        terminationReport = terminationService.terminate(craftId, cause, reason, policy,
                new TerminationService.Actions() {
                    @Override public void classify(TerminationService.Session session) {
                        if (flatOperationSession != null) {
                            session.classify(switch (flatOperationSession.terminalClass()) {
                                case PRE_START -> TerminationCoordinator.OperationState.PRE_START;
                                case IN_FLIGHT -> TerminationCoordinator.OperationState.IN_FLIGHT;
                                case SETTLED -> TerminationCoordinator.OperationState.SETTLED;
                            });
                        }
                        for (CraftNodeRuntime runtime : nodeRuntimes.values()) {
                            runtime.classifyTermination(session.coordinator());
                        }
                    }
                    @Override public void settleCaptured(boolean deliver) {
                        if (deliver) recoverCapturedOutputs(online); else disarmOutputCapture();
                    }
                    @Override public void closeOperationScope() { closeFlatOperationScope(); }
                    @Override public void cleanupGraph() { cleanupGraphNodes(online, reason); }
                    @Override public void recoverGraphSurplus() {
                        if (useGraphExecution && graphMaterials != null) {
                            for (ItemStack stack : graphMaterials.drainAvailableProducerAssets()) {
                                addToVirtualInventory(stack);
                            }
                            snapshotCommittedVirtual();
                        }
                    }
                    @Override public void refundLedger() {
                        refundOrRollbackLedger(online, refundFlatCommitted);
                    }
                    @Override public void deliverSettledAssets() { recoverCommittedVirtual(online); }
                    @Override public void closeLedger() { ledger.close(); }
                    @Override public void notifyOwner() {
                        if (online != null) online.sendSystemMessage(Component.translatable(
                                "rsi.async.chain_aborted", userReason));
                    }
                });
        if (!terminationReport.clean()) {
            RSIntegrationMod.LOGGER.error(ctx.format(
                    "Termination audit incomplete: cause={} unknown={} failedSteps={} steps={}"),
                    terminationReport.cause(), terminationReport.unknownOperations(),
                    terminationReport.failedSteps(), terminationReport.steps());
        } else {
            RSIntegrationMod.LOGGER.info(ctx.format(
                    "Termination audit complete: cause={} preStart={} inFlight={} settled={}"),
                    terminationReport.cause(), terminationReport.preStartOperations(),
                    terminationReport.inFlightOperations(), terminationReport.settledOperations());
        }
        if (online != null) sendTerminalProgress(online);
        fireOnDone();
    }

    /** Stop graph executor and clean up every running node runtime. */
    private void cleanupGraphNodes(@Nullable ServerPlayer player, String reason) {
        if (graphExecutor != null) {
            graphExecutor.stopScheduling();
        }
        for (CraftNodeRuntime runtime : List.copyOf(nodeRuntimes.values())) {
            if (runtime.failureReason() != null && !runtime.failureReason().isEmpty()) {
                graphFailureDetails.put(runtime.nodeId(), runtime.failureReason());
            }
            try {
                runtime.stopDispatch();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error stopping graph node {}"), runtime.describe(), e);
            }
            try {
                for (ItemStack s : runtime.drainSettledResults()) addToVirtualInventory(s);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error recovering settled results from graph node {}"),
                        runtime.describe(), e);
            }
            try {
                for (ItemStack s : runtime.drainQueuedMaterials()) addToVirtualInventory(s);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error recovering queued materials from graph node {}"),
                        runtime.describe(), e);
            }
            try {
                ExtractionLedger nodeLedger = runtime.nodeLedger();
                if (nodeLedger != null && nodeLedger.isCommitted()) {
                    for (ExtractionLedger.ReservationToken token : runtime.queuedReservationTokens()) {
                        try {
                            refundCommitted(nodeLedger, player);
                        } catch (Exception e) {
                            RSIntegrationMod.LOGGER.error(ctx.format(
                                    "Error refunding queued reservation for graph node {}"), runtime.describe(), e);
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error reading queued reservations for graph node {}"),
                        runtime.describe(), e);
            }
            try {
                List<ItemStack> queuedProducer = runtime.drainQueuedProducerMaterials();
                NodeAdmissionCoordinator.Admission admission = runtime.admission();
                if (graphMaterials != null && admission != null && !queuedProducer.isEmpty()) {
                    graphMaterials.refundCommittedProducerFragments(
                            admission.materialToken(), queuedProducer);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error refunding producer materials for graph node {}"),
                        runtime.describe(), e);
            }
            try {
                for (ItemStack s : runtime.disarmCapture()) {
                    if (player != null) safeGiveToPlayer(player, s);
                    else insertOrDropAtSpawn(s);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error recovering captured output from graph node {}"),
                        runtime.describe(), e);
            }
            try {
                runtime.cleanupFailure();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error(ctx.format("Error in graph node delegate cleanup {}"),
                        runtime.describe(), e);
            } finally {
                // The delegate has removed the remaining input from a loaded machine,
                // so this in-flight reservation is now safe to refund. Without this
                // handshake, slot-based machines could clear their input while both
                // the node ledger and graph broker still treated it as consumed.
                if (CraftNodeRuntime.shouldRefundInFlightMaterials(
                        runtime.operationTerminalClass(),
                        runtime.physicalFailureCleanupCompleted())) {
                    try {
                        ExtractionLedger cleanupLedger = runtime.nodeLedger();
                        if (cleanupLedger != null && cleanupLedger.isCommitted()) {
                            if (runtime.failureRecoveredInputs() != null) {
                                cleanupLedger.retainCommittedRefunds(
                                        runtime.failureRecoveredInputs());
                            }
                            refundCommitted(cleanupLedger, player);
                        }
                    } catch (Exception e) {
                        RSIntegrationMod.LOGGER.error(ctx.format(
                                "Error refunding physically recovered inputs for graph node {}"),
                                runtime.describe(), e);
                    }
                    try {
                        if (graphAdmissions != null && runtime.admission() != null) {
                            if (runtime.failureRecoveredInputs() != null) {
                                graphAdmissions.refundRecoveredMaterial(runtime.admission(),
                                        runtime.failureRecoveredInputs());
                            } else {
                                graphAdmissions.refundCommittedMaterial(runtime.admission());
                            }
                        }
                    } catch (Exception e) {
                        RSIntegrationMod.LOGGER.error(ctx.format(
                                "Error releasing recovered graph materials for node {}"),
                                runtime.describe(), e);
                    }
                }
                // Generic recipes have no physical machine that could have
                // accepted the inputs. If their start callback rejects after
                // the node ledger was committed, return those exact stacks;
                // machine-backed delegates are intentionally left to their
                // operation terminal state and cleanup hooks.
                if (runtime.delegate() instanceof GenericBatchDelegate
                        && runtime.failureReason() != null
                        && runtime.nodeLedger() != null
                        && runtime.nodeLedger().isCommitted()) {
                    try {
                        refundCommitted(runtime.nodeLedger(), player);
                        RSIntegrationMod.LOGGER.debug(ctx.format(
                                "Refunded committed materials for failed virtual recipe node {}"),
                                runtime.describe());
                    } catch (Exception e) {
                        RSIntegrationMod.LOGGER.error(ctx.format(
                                "Error refunding failed virtual recipe node {}"),
                                runtime.describe(), e);
                    }
                }
                try {
                    ExtractionLedger cleanupLedger = runtime.nodeLedger();
                    if (cleanupLedger != null) cleanupLedger.close();
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.error(ctx.format("Error closing ledger for graph node {}"),
                            runtime.describe(), e);
                }
                try {
                    closeGraphRuntimeResources(runtime);
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.error(ctx.format("Error releasing resources for graph node {}"),
                            runtime.describe(), e);
                }
            }
        }
        nodeRuntimes.clear();
    }

    private void closeGraphRuntimeResources(CraftNodeRuntime runtime) {
        if (graphAdmissions == null || !runtime.markResourcesClosed()) return;
        NodeAdmissionCoordinator.Admission admission = runtime.admission();
        if (admission == null) return;
        MaterialBroker.ReservationState state = graphMaterials != null
                ? graphMaterials.state(admission.materialToken()) : null;
        if (state == MaterialBroker.ReservationState.RESERVED) {
            graphAdmissions.releaseMaterial(admission);
        } else if (state == MaterialBroker.ReservationState.COMMITTED) {
            OperationExecutionKernel.TerminalClass terminalClass = runtime.operationTerminalClass();
            if (terminalClass == OperationExecutionKernel.TerminalClass.PRE_START) {
                graphAdmissions.refundCommittedMaterial(admission);
            } else {
                // IN_FLIGHT inputs may already be inside a machine. SETTLED inputs
                // are also final. Neither state permits an optimistic broker refund.
            }
        }
    }

    //  progress packets

    private void maybeSendProgress(ServerPlayer online, boolean terminal) {
        if (terminal) {
            sendTerminalProgress(online);
            return;
        }
        progressTickCounter++;
        if (progressTickCounter % 20 != 0) return;
        sendProgressSnapshot(online, buildProgressSnapshot(false));
    }

    private void sendTerminalProgress(ServerPlayer online) {
        sendProgressSnapshot(online, buildProgressSnapshot(true));
    }

    private void sendProgressSnapshot(ServerPlayer online, CraftProgressSnapshot snapshot) {
        progressPublisher.publish(online, snapshot, progressTickCounter);
    }

    private void sendStartedPacket(ServerPlayer online) {
        int total = useGraphExecution && graph != null
                ? graph.topologicalOrder().size() : steps.size();
        BatchCraftNetworkHandler.CHANNEL.sendTo(
                new CraftStartedPacket(craftId, total, useGraphExecution,
                        targetOutput == null ? ItemStack.EMPTY : targetOutput),
                online.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
    }

    static boolean shouldRefundFlatCommitted(
            @Nullable OperationExecutionKernel.TerminalClass terminalClass,
            boolean physicalCleanupCompleted) {
        return terminalClass != OperationExecutionKernel.TerminalClass.IN_FLIGHT
                || physicalCleanupCompleted;
    }

    private void refundOrRollbackLedger(@Nullable ServerPlayer player,
                                        boolean refundCommittedInputs) {
        try {
            if (ledger.isCommitted()) {
                if (refundCommittedInputs) {
                    refundCommitted(ledger, player);
                } else {
                    ledger.settleAllCommitted();
                    RSIntegrationMod.LOGGER.warn(ctx.format(
                            "Committed inputs were not physically recovered; suppressing abort refund"));
                }
            } else {
                ledger.rollback(player);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error(ctx.format("Failed to refund or roll back ledger"), e);
        }
    }

    //  delegate factory

    private IBatchDelegate createStepDelegate(CraftingResolver.ResolutionStep step) {
        boolean codeKnown = false;
        if (step.inferMode()
                && com.huanghuang.rsintegration.util.ModIds.ID_EMBERS_ALCHEMY
                .equals(step.modType().id())) {
            ServerPlayer player = resolvePlayer();
            ServerLevel dataLevel = player != null ? player.serverLevel() : server.overworld();
            KnownCodeSavedData savedData = KnownCodeSavedData.get(dataLevel);
            savedData.setWorldSeed(dataLevel.getSeed());
            codeKnown = savedData.hasCode(step.recipeId().toString());
        }
        boolean useInference = EreAlchemyDelegateMode.shouldUseInference(
                step.modType().id(), step.inferMode(), codeKnown);
        return useInference ? step.modType().createInferDelegate() : createDelegate(step.modType());
    }

    private static IBatchDelegate createDelegate(ModType type) {
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

    //  safe item give

    private void safeGiveToPlayer(ServerPlayer player, ItemStack stack) {
        PlayerUtils.safeGiveToPlayer(player, stack, network);
    }

    //  debug helpers

    /** Rendered name — log use only. See {@link #nameIngredientSafe} for player-facing text. */
    private static String describeIngredientSafe(Ingredient ing) {
        return nameIngredientSafe(ing).getString();
    }

    /**
     * Unresolved item name for player-facing messages. Must stay a Component:
     * this runs server-side, where item translation keys cannot be resolved.
     */
    private static Component nameIngredientSafe(Ingredient ing) {
        return CraftPacketUtils.describeIngredient(ing);
    }

    private void logMissingIngredient(Ingredient ing, ResourceLocation stepId) {
        StringBuilder sb = new StringBuilder(ctx.format("Missing ingredient for step "));
        sb.append(stepId).append(" -options: ");
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
        logLedgerState(ledger);
    }

    private void logLedgerState(ExtractionLedger sourceLedger) {
        if (!RSIntegrationMod.LOGGER.isDebugEnabled()) return;
        RSIntegrationMod.LOGGER.debug(ctx.format("Ledger entries={} committed={}"),
                sourceLedger.size(), sourceLedger.isCommitted());
        RSIntegrationMod.LOGGER.debug(ctx.format("Network available (sample): {}"),
                sourceLedger.describePending());
    }
}
