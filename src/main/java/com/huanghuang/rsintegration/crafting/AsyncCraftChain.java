package com.huanghuang.rsintegration.crafting;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.api.execution.CancelReason;


import com.huanghuang.rsintegration.network.packet.NetworkHandler;import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.feature.ftbquests.ExternalItemProgressBridge;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.CraftExecutionPolicy;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotBatchDelegate;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyDelegateMode;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog;
import com.huanghuang.rsintegration.mods.pmmo.PmmoSalvageCatalog;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.mods.embers.KnownCodeSavedData;
import com.huanghuang.rsintegration.util.LogSampler;
import com.huanghuang.rsintegration.util.CuriosAccess;
import com.huanghuang.rsintegration.util.ItemStackUtils;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.crafting.plan.MagicResourceCostCatalog;
import com.huanghuang.rsintegration.magic.MagicResourceCost;
import com.huanghuang.rsintegration.magic.MagicResourceTaskReservation;
import java.util.Arrays;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.external.ExternalIntegrationRuntime;
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
import com.huanghuang.rsintegration.crafting.graph.InputDemand;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeAdmissionCoordinator;
import com.huanghuang.rsintegration.crafting.graph.NodeOutputAccumulator;
import com.huanghuang.rsintegration.crafting.graph.OperationId;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import com.huanghuang.rsintegration.crafting.graph.GraphConcurrencyPolicy;
import com.huanghuang.rsintegration.crafting.graph.GraphConcurrencyEligibility;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.ModVersionDelegateRegistry;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.loadbalancer.LoadBalancer;
import com.huanghuang.rsintegration.mods.ironfurnaces.IronFurnaceMachinePriority;
import com.huanghuang.rsintegration.crafting.loadbalancer.ParallelCraftGroup;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import com.huanghuang.rsintegration.network.ProtectionChecker;
import com.huanghuang.rsintegration.util.CraftLogContext;
import com.huanghuang.rsintegration.util.Diagnostics;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler;
import com.huanghuang.rsintegration.mods.vanilla.brewing.VanillaBrewingCatalog;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerCatalog;
import com.huanghuang.rsintegration.util.PlayerUtils;
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
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;

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
    /**
     * 机器被占用时的重试上限。机器通常几个 tick 内就会释放，用 40 tick 的退避会让
     * 一条需要同一台机器的链退化成“每 2 秒才推进一步”（例如切石机连环切割）。
     */
    private static final int GRAPH_RETRY_MACHINE_BUSY_MAX_DELAY_TICKS = 6;
    /** 绑定机器全被占用/未加载时的等待原因。 */
    private static final String MACHINE_BUSY_DETAIL =
            "all bound machines are busy, unloaded, or unavailable";
    /** 属于“机器暂时不可用”的等待原因；这些原因不需要长时间退避。 */
    private static final List<String> MACHINE_WAIT_DETAILS = List.of(
            MACHINE_BUSY_DETAIL,
            "operation budget, machine, or capture is temporarily unavailable");
    /** 同一个节点连续等待到达这个次数后，向玩家提示一次具体原因，避免只看到“准备中”。 */
    private static final int GRAPH_RETRY_NOTIFY_ATTEMPTS = 5;
    /** 材料连续重试的兜底；正常等机器不使用这个固定窗口。 */
    private static final int GRAPH_RETRY_ABORT_TICKS = 600;

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
    /** 当前合成链固定使用的存储端点。 */
    @Nullable
    private final CraftStorageEndpoint storageEndpoint;
    private final List<CraftingResolver.ResolutionStep> steps;
    private final List<ItemStack> virtualInventory = new ArrayList<>();
    private final List<ItemStack> deliveredFinalOutputs = new ArrayList<>();
    private final List<ItemStack> recoveryFinalOutputs = new ArrayList<>();
    private final List<CollectedOutput> reportedResources = new ArrayList<>();
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
    private long flatObservedProductionSequence;
    private long flatObservedCapturedCount;
    private int stepRemaining;
    private final Map<CraftingResolver.ResolutionStep, ProbabilisticProduction> probabilityTargets = new IdentityHashMap<>();
    private Map<ResourceLocation, ResourceLocation> supplementalForcedRecipes = Map.of();
    private Map<String, ItemStack> supplementalMaterialLocks = Map.of();
    private State state = State.PENDING;
    private int taintSlot = -1;
    private int taintRemaining;
    private int taintWaitTicks;
    @Nullable
    private NodeId earthHeartTaintNodeId;
    /** 图模式下从 MaterialBroker 预留里取出、等待逐个污染的大地之心。 */
    private final List<ItemStack> taintHeartQueue = new ArrayList<>();
    private String abortReason = "";
    private CraftFailureCode failureCode = CraftFailureCode.NONE;
    private TerminationCoordinator.Cause terminalCause;
    private final TerminalListeners terminalListeners;
    private final TerminationService terminationService = new TerminationService();
    private int machineCount = 1;
    private boolean waitingForMachineLease;
    private List<MachineLeaseRegistry.MachineKey> flatWaitingMachines = List.of();
    private final MachineWaitNotifier machineWaitNotifier = new MachineWaitNotifier();
    private int machineLeaseWaitTicks;
    @Nullable
    private Component machineWaitMessage;
    @Nullable
    private String machineWaitDetail;
    @Nullable
    private Component machineStartFailureMessage;
    @Nullable
    private String machineStartFailureDetail;
    private String machineStartFailureLabel = "";
    @Nullable
    private String materialReservationFailureDetail;
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
    @Nullable
    private IntegrationResourceKey targetResource;
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
    @Nullable
    private final DagScheduler graphScheduler;
    private final boolean useGraphExecution;
    @Nullable
    private NodeId currentGraphNode;
    @Nullable
    private ConcurrentNodeExecutor graphExecutor;
    private final Map<NodeId, CraftNodeRuntime> nodeRuntimes = new HashMap<>();
    private final Map<NodeId, ProductionAttemptState> productionAttempts = new HashMap<>();
    private final Map<NodeId, String> graphFailureDetails = new HashMap<>();
    private final Map<NodeId, CraftFailureCode> graphFailureCodes = new HashMap<>();
    /** Prevents an unavailable machine/material from being rescanned every tick. */
    private final Map<NodeId, Integer> graphRetryUntilTick = new HashMap<>();
    private final Map<NodeId, Integer> graphRetryAttempts = new HashMap<>();
    private final Map<String, Integer> graphRetryCounts = new HashMap<>();
    /** 每个节点第一次进入等待的 tick，用于判定“卡死”。 */
    private final Map<NodeId, Integer> graphRetrySinceTick = new HashMap<>();
    /** 已经提示过的等待原因（按原因去重）：同一条链上十几个节点等同一台机器时只提示一次。 */
    private final Set<String> graphRetryNotified = new HashSet<>();
    /** 正在等机器的节点，机器租约一释放就立刻给它们一次机会。 */
    private final Set<NodeId> graphMachineWaits = new HashSet<>();
    /** 只跟踪本节点实际等待的已租用机器，不响应无关机器的释放。 */
    private final Map<NodeId, List<MachineLeaseRegistry.MachineKey>> graphWaitingMachines = new HashMap<>();
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
    @Nullable
    private MagicResourceTaskReservation magicResourceTaskReservation;
    private int progressTickCounter;
    private boolean waitingForVanillaBudget;
    private BooleanSupplier dispatchAllowed = () -> true;
    private int vanillaBatchDeferredTicks;
    private final Map<NodeId, GraphVanillaJob> graphVanillaJobs = new LinkedHashMap<>();
    private final CraftProgressPublisher progressPublisher;
    private int progressSequence;
    @Nullable
    private TerminationCoordinator.Report terminationReport;

    private static final class ProductionAttemptState {
        private final NodeOutputAccumulator outputs;
        private final OutputPortId targetPort;
        private final long targetQuantity;
        private final int maxAttempts;
        private int attempts;

        private ProductionAttemptState(NodeOutputAccumulator outputs,
                                       OutputPortId targetPort,
                                       long targetQuantity, int maxAttempts) {
            this.outputs = outputs;
            this.targetPort = targetPort;
            this.targetQuantity = Math.max(1L, targetQuantity);
            this.maxAttempts = Math.max(1, maxAttempts);
        }

        boolean canAttempt() {
            return attempts < maxAttempts;
        }

        void started() {
            if (!canAttempt()) {
                throw new IllegalStateException("production attempt budget exhausted");
            }
            attempts++;
        }

        boolean complete() {
            return outputs.publishedQuantity(targetPort) >= targetQuantity;
        }
    }

    /**
     * 一个已准入 GENERIC 图节点的跨 tick 执行租约。
     *
     * <p>节点材料仍按整节点一次性预留，避免其他节点看到半完成的材料状态；
     * 配方执行则严格按 tick 预算切片，全部分片成功后才发布节点产物。</p>
     */
    private static final class GraphVanillaJob {
        private final NodeId nodeId;
        private final CraftingResolver.ResolutionStep step;
        private final NodeAdmissionCoordinator.Admission admission;
        private final ExtractionLedger nodeLedger;
        private final ExtractionLedger executionLedger;
        private final OperationExecutionKernel.Session operation;
        private final List<ItemStack> inventory;
        private int remainingExecutions;

        private GraphVanillaJob(
                NodeId nodeId,
                CraftingResolver.ResolutionStep step,
                NodeAdmissionCoordinator.Admission admission,
                ExtractionLedger nodeLedger,
                ExtractionLedger executionLedger,
                OperationExecutionKernel.Session operation,
                List<ItemStack> inventory,
                int remainingExecutions
        ) {
            this.nodeId = nodeId;
            this.step = step;
            this.admission = admission;
            this.nodeLedger = nodeLedger;
            this.executionLedger = executionLedger;
            this.operation = operation;
            this.inventory = inventory;
            this.remainingExecutions = remainingExecutions;
        }
    }

    public AsyncCraftChain(UUID playerId, MinecraftServer server,
                           @Nullable CraftStorageEndpoint storageEndpoint,
                           List<CraftingResolver.ResolutionStep> steps) {
        this(UUID.randomUUID(), playerId, server, storageEndpoint, steps, null);
    }

    public AsyncCraftChain(UUID playerId, MinecraftServer server,
                           @Nullable CraftStorageEndpoint storageEndpoint,
                           CraftPlanGraph graph) {
        this(UUID.randomUUID(), playerId, server, storageEndpoint,
                ExecutionEquivalence.projectFlatSteps(graph), graph);
    }

    AsyncCraftChain(UUID craftId, UUID playerId, MinecraftServer server,
                    @Nullable CraftStorageEndpoint storageEndpoint,
                    List<CraftingResolver.ResolutionStep> steps) {
        this(craftId, playerId, server, storageEndpoint, steps, null);
    }

    private AsyncCraftChain(UUID craftId, UUID playerId, MinecraftServer server,
                            @Nullable CraftStorageEndpoint storageEndpoint,
                            List<CraftingResolver.ResolutionStep> steps,
                            @Nullable CraftPlanGraph graph) {
        this.craftId = Objects.requireNonNull(craftId, "craftId");
        this.progressPublisher = new CraftProgressPublisher(craftId);
        this.playerId = playerId;
        this.server = server;
        this.storageEndpoint = storageEndpoint;
        this.steps = new ArrayList<>(steps);
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
        // 并行/派发上限不再由配置写死：图节点、每 tick 派发和机器操作都由
        // DAG 依赖、机器租约与材料预留自然决定。
        this.graphRunningNodeCap = Integer.MAX_VALUE;
        this.graphDispatchPerTick = Integer.MAX_VALUE;
        this.graphDispatchPerCraft = Integer.MAX_VALUE;
        this.craftOperationBudget = new OperationBudget(
                Integer.MAX_VALUE, Integer.MAX_VALUE);
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
            this.graphScheduler = new DagScheduler(graph);
            this.graphMaterials = new MaterialBroker();
            this.graphAdmissions = new NodeAdmissionCoordinator(graphScheduler, graphMaterials);
            initialiseGraphMaterialFlow(graph);
            logGraphNodeMappings(graph);
            this.useGraphExecution = true;
            RSIntegrationMod.LOGGER.debug(ctx.format(
                    "Using graph execution: nodes={} terminalEmbedded=true"),
                    graph.topologicalOrder().size());
        } else {
            this.graph = null;
            this.graphScheduler = null;
            this.graphMaterials = null;
            this.graphAdmissions = null;
            this.useGraphExecution = false;
        }
        this.magicResourceTaskReservation =
                this.storageEndpoint == null
                        || CraftStorageEndpoints.isRefinedStorage(this.storageEndpoint)
                ? MagicResourceTaskReservation.reserve(server, craftId, magicCostsForPlan())
                : null;
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
        Map<InitialLotKey, Long> initial = new LinkedHashMap<>();
        for (MaterialAllocation allocation : plan.allocations()) {
            addBrokerRequests(
                    graphRequests.computeIfAbsent(allocation.consumer().nodeId(),
                            ignored -> new ArrayList<>()),
                    allocation.source(), allocation.material(), allocation.quantity());
            if (allocation.source() instanceof MaterialSource.InitialPool source) {
                initial.merge(new InitialLotKey(source, allocation.material()),
                        allocation.quantity(), SaturatingLongMath::add);
            }
        }
        if (graphMaterials != null) {
            for (Map.Entry<InitialLotKey, Long> entry : initial.entrySet()) {
                graphMaterials.publish(
                        entry.getKey().source(), entry.getKey().material(), entry.getValue());
            }
        }
    }

    static void addBrokerRequests(List<MaterialBroker.Request> requests,
                                  MaterialSource source, MaterialKey material,
                                  long quantity) {
        requests.add(new MaterialBroker.Request(source, material, quantity));
    }

    private record InitialLotKey(MaterialSource.InitialPool source, MaterialKey material) {}

    /**
     * Set the concrete output the player asked for (from the JEI ghost slot).
     * Threaded to the delegate producing the primary recipe so it can pick the
     * correct NBT variant among outputs that share one recipe id. Must be called
     * before the chain starts ticking.
     */
    public void setTargetOutput(@Nullable ItemStack target) {
        this.targetOutput = target != null && !target.isEmpty() ? target.copy() : null;
        this.targetResource = this.targetOutput == null
                ? null
                : IntegrationResourceKey.item(this.targetOutput);
    }

    public void setTargetResource(
            @Nullable IntegrationResourceKey target
    ) {
        this.targetResource = target;
        this.targetOutput = target != null
                && target.kind()
                == IntegrationResourceKey.ResourceKind.ITEM
                ? itemStack(target)
                : null;
    }

    private static ItemStack itemStack(
            IntegrationResourceKey resource
    ) {
        ItemStack stack = new ItemStack(
                BuiltInRegistries.ITEM.get(resource.id()), 1);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        if (!resource.identity().isEmpty()) {
            stack.setTag(resource.identity());
        }
        return stack;
    }

    public void setOutputDestination(@Nullable OutputDestination destination) {
        this.outputDestination = destination == null ? OutputDestination.RS_NETWORK : destination;
    }

    public void setSupplementalPlanningPreferences(Map<String, String> forcedRecipes,
                                                   Map<String, ItemStack> materialLocks) {
        Map<ResourceLocation, ResourceLocation> forced = new HashMap<>();
        forcedRecipes.forEach((output, recipe) -> {
            ResourceLocation itemId = ResourceLocation.tryParse(output);
            ResourceLocation recipeId = ResourceLocation.tryParse(recipe);
            if (itemId != null && recipeId != null) forced.put(itemId, recipeId);
        });
        supplementalForcedRecipes = Map.copyOf(forced);
        supplementalMaterialLocks = MaterialLocks.immutableCopy(materialLocks);
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
        return MachineDispatchPlanner.routeCandidates(machines, preference.mode(),
                preference.dimension(), preference.position());
    }

    public ItemStack displayTarget() {
        if (targetOutput != null && !targetOutput.isEmpty()) return targetOutput.copy();
        if (graph != null) {
            ItemStack declared = graph.rootDemands().stream()
                    .flatMap(root -> root.allocations().stream()
                            .filter(allocation -> allocation.source() instanceof MaterialSource.ProducerOutput)
                            .map(allocation -> allocation.material().toStack(1)))
                    .filter(stack -> !stack.isEmpty())
                    .findFirst().orElse(ItemStack.EMPTY);
            if (!declared.isEmpty()) return declared;
        }
        // 仅补展示信息，不改 targetOutput 的运行时配方选择与产物路由语义。
        return steps.isEmpty() ? ItemStack.EMPTY : displayOutput(steps.get(steps.size() - 1));
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
        CraftingResolver.ResolutionStep step =
                steps.get(currentStepIdx);
        ProductionTarget productionTarget =
                step.productionTarget();
        if (productionTarget != null) {
            ItemStack display = productionTarget.displayStack();
            IntegrationResourceKey resource =
                    productionTarget.resource();
            if (!display.isEmpty()
                    && resource.kind()
                    == IntegrationResourceKey.ResourceKind.ITEM) {
                abd.setTargetOutput(display);
            }
            abd.setTargetResource(resource);
            return;
        }
        ItemStack planned = step.syntheticOutput();
        if (planned != null && !planned.isEmpty()) {
            abd.setTargetOutput(planned);
            abd.setTargetResource(IntegrationResourceKey.item(planned));
        } else if (targetOutput != null && isPrimaryStep(currentStepIdx)) {
            abd.setTargetOutput(targetOutput);
            abd.setTargetResource(targetResource);
        } else if (isPrimaryStep(currentStepIdx)
                && targetResource != null) {
            abd.setTargetResource(targetResource);
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
        return tick(vanillaAllowance, () -> true);
    }

    boolean tick(VanillaCraftingTickBudget.ChainAllowance vanillaAllowance,
                 BooleanSupplier dispatchAllowed) {
        this.dispatchAllowed = Objects.requireNonNull(dispatchAllowed, "dispatchAllowed");
        waitingForVanillaBudget = false;
        if (state == State.ABORTED || state == State.COMPLETED) return true;

        if (state == State.WAITING_PLAYER_TRANSFORMATION) {
            ServerPlayer online = resolvePlayer();
            if (online == null) {
                abortWithoutRefund("Player disconnected while Earth Heart was in inventory",
                        Component.translatable("rsi.async.earth_heart.error.player_disconnected"),
                        CraftFailureCode.PLAYER_OFFLINE);
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
            if (storageEndpoint != null && !storageEndpoint.isAvailable()) {
                abortSilently("Storage endpoint removed or network invalidated");
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
        if (storageEndpoint != null && !storageEndpoint.isAvailable()) {
            abortSilently("Storage endpoint removed or network invalidated");
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
                    if (group.isWaitingForLeasedMachine()) waitTicks = 0;
                    long productionSequence = group.getProductionSequence();
                    if (productionSequence > flatObservedProductionSequence) {
                        flatObservedProductionSequence = productionSequence;
                        waitTicks = 0;
                    }
                    int completed = group.getCompletedOperations();
                    if (completed > flatObservedCompletedOperations) {
                        flatObservedCompletedOperations = completed;
                        waitTicks = 0;
                    }
                    List<ItemStack> settled = group.drainSettledResults();
                    if (!settled.isEmpty()) {
                        reportCraftedPrimaryOutputs(online, settled,
                                currentDelegate.getExpectedProduction(),
                                currentStepOutputPrototype());
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
                        ItemStack expectedWorldOutput = currentDelegate.getExpectedOutput();
                        List<ItemStack> actualResults = new ArrayList<>(OutputAccounting.mergeLegacyOutputs(
                                disarmOutputCapture(), currentDelegate.collectAllResults(online),
                                expectedWorldOutput));
                        recordReportedResources(currentDelegate);
                        closeFlatOperationScope();
                        actualResults.removeIf(stack -> stack == null || stack.isEmpty());
                        reportCraftedPrimaryOutputs(online, actualResults,
                                currentDelegate.getExpectedProduction(),
                                currentStepOutputPrototype());
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
                                + observation.detail(), failureMessage,
                                CraftFailureCode.UNKNOWN);
                        return true;
                    }
                    Component failureMessage = currentDelegate.craftFailureMessage(observation);
                    abort("Machine craft failed: " + observation.detail(),
                            failureMessage != null ? failureMessage : Component.translatable(
                                    "rsi.async.abort.machine_craft_failed", observation.detail()),
                            CraftFailureCode.UNKNOWN);
                    return true;
                }
                List<ItemStack> capturedSnapshot = capturedOutputSnapshot();
                long capturedCount = capturedSnapshot.stream().mapToLong(ItemStack::getCount).sum();
                if (capturedCount > flatObservedCapturedCount) {
                    flatObservedCapturedCount = capturedCount;
                    waitTicks = 0;
                }
                // 捕获会取消原生掉落实体，只有整批产物到齐才能代替机器完成信号。
                boolean capturedWorldOutput = capturedOutput
                        && OutputAccounting.hasCapturedExpectedProduction(currentDelegate, capturedSnapshot);
                if (observation.phase() == IBatchDelegate.CraftPhase.DONE || capturedWorldOutput) {
                    if (!currentDelegate.validateExecutionContext(online)) {
                        abort("Execution context changed before output publication",
                                Component.translatable("rsi.async.abort.execution_context"));
                        return true;
                    }
                    OutputCollection collectedOutputs = collectOutputsForSettlement(
                            currentDelegate, online, disarmOutputCapture());
                    List<CollectedOutput> reported =
                            recordReportedResources(currentDelegate);
                    List<CollectedOutput> observed =
                            recordObservedProduction(currentDelegate);
                    closeFlatOperationScope();
                    List<ItemStack> actualResults = collectedOutputs.stacks();
                    for (ItemStack result : actualResults) addToVirtualInventory(result);
                    String resourceFailure =
                            currentDelegate.reportedResourceFailure();
                    if (resourceFailure != null) {
                        snapshotCommittedVirtual();
                        ledger.reset();
                        abortWithoutRefund(
                                "Direct resource delivery failed: "
                                        + resourceFailure,
                                Component.literal(resourceFailure),
                                CraftFailureCode.OUTPUT_MISSING
                        );
                        return true;
                    }
                    ProbabilisticProduction probability = probabilityTargets.get(steps.get(currentStepIdx));
                    if (probability != null) {
                        probability.settle(
                                actualResults, reported, observed);
                    }

                    IBatchDelegate.ExpectedProduction expected = currentDelegate.getExpectedProduction();
                    int actualCount = countMatchingProduction(actualResults, expected);
                    reportCraftedPrimaryOutputs(online, actualResults, expected,
                            currentStepOutputPrototype());
                    if (collectedOutputs.settlement() != null
                            && !collectedOutputs.settlement().complete()) {
                        snapshotCommittedVirtual();
                        ledger.reset();
                        abortWithoutRefund("Structured output settlement failed: "
                                        + describeOutputSettlement(collectedOutputs.settlement()),
                                Component.translatable("rsi.async.error.output_extracted",
                                        expectedStructuredOutputCount(collectedOutputs.settlement()),
                                        actualStructuredOutputCount(collectedOutputs.settlement())),
                                CraftFailureCode.OUTPUT_MISSING);
                        return true;
                    }
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
                                        expected.count(), actualCount),
                                CraftFailureCode.OUTPUT_MISSING);
                        return true;
                    }

                    // World-output delegates use a separate capture declaration and
                    // may opt out of count comparison while still failing closed.
                    ItemStack expectedWorld = currentDelegate.getExpectedOutput();
                    if (collectedOutputs.settlement() == null && expected == null
                            && expectedWorld != null && !expectedWorld.isEmpty()
                            && actualResults.isEmpty()) {
                        snapshotCommittedVirtual();
                        ledger.reset();
                        abortWithoutRefund("Expected craft output was not captured: "
                                + steps.get(currentStepIdx).recipeId(),
                                Component.translatable("rsi.async.abort.output_not_captured",
                                        steps.get(currentStepIdx).recipeId().toString()),
                                CraftFailureCode.OUTPUT_MISSING);
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
                        machineCount = group.getTotalOperations();
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
                    flatObservedProductionSequence = 0;
                    flatObservedCapturedCount = 0;
                    ledger.reset();
                    stepRemaining = probability == null
                            ? remainingAfterFlatBatch(stepRemaining, machineCount)
                            : probability.complete() ? 0 : 1;
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
        if (isEarthHeartTaintStep(step)) {
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
            ProbabilisticProduction probability = probabilityTarget(step, online);
            if (probability != null) {
                if (!probability.canAttempt()) {
                    abort("Probabilistic production attempt limit reached: " + step.recipeId(),
                            Component.translatable("rsi.async.probability.limit", probability.produced(),
                                    probability.target().quantity(), probability.attempts()),
                            CraftFailureCode.OUTPUT_MISSING);
                    return true;
                }
                if (!prepareProbabilityInputs(step, probability, online)) {
                    maybeSendProgress(online, false);
                    return state == State.ABORTED;
                }
                // 一轮一试，避免达到目标后仍在途消耗额外材料。
                stepRemaining = 1;
            }
            if (stepRemaining <= 0) {
                stepRemaining = step.executions();
                machineCount = 1;
            }
            currentDelegate = startModStep(step, online);
            flatObservedCompletedOperations = 0;
            flatObservedProductionSequence = 0;
            flatObservedCapturedCount = 0;
            if (currentDelegate == null) {
                if (waitingForMachineLease) {
                    // 已知占用方仍在运行时只排队，不消耗本单的无进展窗口。
                    if (hasLeasedMachine(flatWaitingMachines, machineLeases)) {
                        machineLeaseWaitTicks = 0;
                    } else {
                        machineLeaseWaitTicks++;
                    }
                    machineWaitNotifier.notifyWait(online, machineWaitMessage);
                    int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
                    if (machineLeaseWaitTicks > timeoutTicks) {
                        abort("Timeout waiting for an available multi-block machine: "
                                        + step.recipeId()
                                        + (machineWaitDetail == null ? "" : " (" + machineWaitDetail + ")"),
                                machineWaitMessage == null
                                        ? Component.translatable("rsi.async.abort.machine_wait_timeout",
                                                step.recipeId().toString())
                                        : Component.translatable("rsi.async.abort.machine_wait_reason",
                                                step.recipeId().toString(), machineWaitMessage),
                                CraftFailureCode.MACHINE_BUSY);
                        return true;
                    }
                    maybeSendProgress(online, false);
                    return false;
                }
                abort(machineStartFailureDetail != null
                                ? machineStartFailureDetail
                                : materialReservationFailureDetail != null
                                ? materialReservationFailureDetail
                                : "Failed to start multi-block craft: " + step.recipeId(),
                        machineStartFailureMessage != null
                                ? machineStartFailureMessage
                                : Component.translatable("rsi.async.abort.machine_start_failed",
                                        step.recipeId().toString()),
                        machineStartFailureDetail != null
                                ? CraftFailureCode.START_REJECTED
                                : CraftFailureCode.MATERIAL_EXTRACTION_FAILED);
                machineStartFailureMessage = null;
                return true;
            }
            waitingForMachineLease = false;
            if (probability != null) probability.started();
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
            graphExecutor.tick(dispatchAllowed);
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
                            Component.translatable("rsi.async.abort.graph_stalled"),
                            CraftFailureCode.INTERNAL_ERROR);
                } else {
                    abort("Graph node " + failedNode.value() + " failed: " + detail,
                            Component.translatable("rsi.async.abort.graph_node_failed", detail),
                            graphFailureCodes.getOrDefault(
                                    failedNode, CraftFailureCode.UNKNOWN));
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
        if (graphMachineWaits.stream().anyMatch(nodeId ->
                hasLeasedMachine(graphWaitingMachines.getOrDefault(nodeId, List.of()), machineLeases))) {
            // 占用方有自己的无进展超时；排队方不因前单正常加工时间长而失败。
            graphProgressWatchdog.markProgress();
        }
        if (graphProgressWatchdog.tick()) {
            abort("Crafting chain made no progress for "
                    + (graphProgressWatchdog.timeoutTicks() / 20) + "s",
                    Component.translatable("rsi.async.abort.global_timeout",
                            graphProgressWatchdog.timeoutTicks() / 20),
                    CraftFailureCode.TIMEOUT);
            return true;
        }

        maybeSendProgress(online, false);
        return false;
    }

    /** Resume bounded GENERIC slices, then admit ready graph nodes in order. */
    private void settleReadyVanillaNodes(
            ServerPlayer online, VanillaCraftingTickBudget.ChainAllowance vanillaAllowance) {
        if (!resumeGraphVanillaJobs(online, vanillaAllowance)) return;
        while (true) {
            if (vanillaAllowance.remaining() <= 0) {
                waitingForVanillaBudget = !graphVanillaJobs.isEmpty();
                break;
            }
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
            ServerLevel overworld = server.overworld();
            boolean stableBatchEligible = overworld != null
                    && StableRecipeBatch.isEligible(
                    overworld, step, targetOutput, supplementalMaterialLocks);
            int grantedExecutions = claimGraphVanillaExecutions(
                    vanillaAllowance, executions, stableBatchEligible);
            if (grantedExecutions <= 0) {
                graphAdmissions.releaseBeforeDispatch(admission);
                waitingForVanillaBudget = true;
                break;
            }
            currentStepIdx = idx;
            GraphVanillaJob job = beginGraphVanillaJob(
                    vanillaNode, step, admission, executions, online);
            if (job == null) break;
            graphVanillaJobs.put(vanillaNode, job);
            if (!runGraphVanillaSlice(job, grantedExecutions, online)) return;
        }
    }

    private boolean resumeGraphVanillaJobs(
            ServerPlayer online,
            VanillaCraftingTickBudget.ChainAllowance vanillaAllowance
    ) {
        for (GraphVanillaJob job : List.copyOf(graphVanillaJobs.values())) {
            int granted = claimGraphVanillaExecutions(
                    vanillaAllowance, job.remainingExecutions, false);
            if (granted <= 0) {
                waitingForVanillaBudget = true;
                return false;
            }
            currentStepIdx = stepIndex(job.nodeId);
            if (!runGraphVanillaSlice(job, granted, online)) return false;
        }
        return true;
    }

    @Nullable
    private GraphVanillaJob beginGraphVanillaJob(
            NodeId nodeId,
            CraftingResolver.ResolutionStep step,
            NodeAdmissionCoordinator.Admission admission,
            int executions,
            ServerPlayer online
    ) {
        if (graphMaterials == null || graphAdmissions == null) {
            graphScheduler.releaseClaim(nodeId);
            return null;
        }
        ExtractionLedger nodeLedger = new ExtractionLedger();
        nodeLedger.setLogContext(ctx);
        if (storageEndpoint != null) nodeLedger.setStorageEndpoint(storageEndpoint);
        ExtractionLedger executionLedger = new ExtractionLedger();
        executionLedger.setLogContext(ctx);
        if (storageEndpoint != null) executionLedger.setStorageEndpoint(storageEndpoint);
        OperationExecutionKernel.Session operation = operationKernel.prepareLogical();
        MaterialBroker.Checkout checkout = graphMaterials.checkout(admission.materialToken());
        List<ItemStack> operationInventory = new ArrayList<>(checkout.producerStacks());
        for (ItemStack initial : checkout.initialStacks()) {
            ItemStack reserved = reserveExact(nodeLedger, initial, initial.getCount(), online);
            if (reserved.isEmpty()) {
                graphAdmissions.releaseMaterial(admission);
                nodeLedger.rollback(online);
                operation.close();
                executionLedger.close();
                graphScheduler.releaseClaim(nodeId);
                return null;
            }
            operationInventory.add(reserved);
        }
        if (!operation.commit(() -> commitLedger(nodeLedger, online))) {
            graphAdmissions.releaseMaterial(admission);
            if (nodeLedger.isCommitted()) refundCommitted(nodeLedger, online);
            else nodeLedger.rollback(online);
            operation.close();
            executionLedger.close();
            graphScheduler.releaseClaim(nodeId);
            return null;
        }
        graphAdmissions.commit(admission);
        return new GraphVanillaJob(nodeId, step, admission, nodeLedger,
                executionLedger, operation, operationInventory, executions);
    }

    private boolean runGraphVanillaSlice(
            GraphVanillaJob job, int executions, ServerPlayer online) {
        boolean executed;
        try {
            executed = executeGraphVanillaSlice(job, executions, online);
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.error(ctx.format("Graph vanilla operation threw for {}"),
                    job.nodeId, exception);
            graphFailureDetails.put(job.nodeId,
                    "synchronous vanilla execution threw: " + exception.getMessage());
            graphFailureCodes.put(job.nodeId, CraftFailureCode.INTERNAL_ERROR);
            executed = false;
        }
        if (!executed || state == State.ABORTED) {
            recoverGraphVanillaJob(job);
            graphFailureDetails.putIfAbsent(job.nodeId,
                    "synchronous vanilla execution failed before producing outputs");
            graphFailureCodes.putIfAbsent(job.nodeId,
                    CraftFailureCode.MATERIAL_EXTRACTION_FAILED);
            if (graphScheduler.state(job.nodeId) == DagScheduler.NodeState.RUNNING) {
                graphScheduler.fail(job.nodeId);
            }
            return false;
        }

        job.remainingExecutions -= executions;
        if (job.remainingExecutions > 0) {
            waitingForVanillaBudget = true;
            graphProgressWatchdog.markProgress();
            return true;
        }
        return completeGraphVanillaJob(job);
    }

    private boolean executeGraphVanillaSlice(
            GraphVanillaJob job, int executions, ServerPlayer online) {
        CraftingResolver.ResolutionStep slice = job.step.withExecutions(executions);
        OperationExecutionKernel.StartAction run = () -> executeVanillaStepsInline(
                List.of(slice), online, job.inventory, job.executionLedger, false)
                && commitLedger(job.executionLedger, online);
        return job.operation.startAttempted() ? run.start() : job.operation.tryStart(run);
    }

    private boolean completeGraphVanillaJob(GraphVanillaJob job) {
        boolean outputsComplete = publishDeclaredNodeOutputs(job.nodeId, job.inventory);
        OperationExecutionKernel.CompletionResult completion = job.operation.complete(
                () -> outputsComplete,
                () -> settleGraphVanillaJob(job));
        closeGraphVanillaJob(job);
        if (completion == OperationExecutionKernel.CompletionResult.OUTPUT_SHORTAGE) {
            String detail = "output shortage after synchronous crafting";
            graphFailureDetails.put(job.nodeId, detail);
            graphFailureCodes.put(job.nodeId, CraftFailureCode.OUTPUT_MISSING);
            RSIntegrationMod.LOGGER.warn(ctx.format("Graph node {} completion failed: {}"),
                    job.nodeId, detail);
            graphScheduler.fail(job.nodeId);
            return false;
        }
        snapshotCommittedVirtual();
        graphScheduler.succeed(job.nodeId);
        graphProgressWatchdog.markProgress();
        currentStepIdx = stepIndex(job.nodeId) + 1;
        return true;
    }

    private void recoverGraphVanillaJob(GraphVanillaJob job) {
        for (ItemStack stack : job.inventory) {
            if (stack != null && !stack.isEmpty()) addToVirtualInventory(stack.copy());
        }
        job.inventory.clear();
        try {
            settleGraphVanillaJob(job);
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.error(ctx.format(
                    "Failed to settle recovered graph vanilla node {}"), job.nodeId, exception);
        } finally {
            closeGraphVanillaJob(job);
            snapshotCommittedVirtual();
        }
    }

    private void settleGraphVanillaJob(GraphVanillaJob job) {
        if (graphAdmissions != null
                && graphMaterials != null
                && graphMaterials.state(job.admission.materialToken())
                == MaterialBroker.ReservationState.COMMITTED) {
            graphAdmissions.settleMaterial(job.admission);
        }
        if (job.nodeLedger.isCommitted()) job.nodeLedger.settleAllCommitted();
        if (job.executionLedger.isCommitted()) job.executionLedger.settleAllCommitted();
    }

    private void closeGraphVanillaJob(GraphVanillaJob job) {
        graphVanillaJobs.remove(job.nodeId);
        job.operation.close();
        job.nodeLedger.close();
        job.executionLedger.close();
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

    static int claimGraphVanillaExecutions(
            VanillaCraftingTickBudget.ChainAllowance allowance,
            int remainingExecutions,
            boolean stableBatchEligible
    ) {
        if (remainingExecutions <= 0) {
            throw new IllegalArgumentException("remainingExecutions must be positive");
        }
        if (stableBatchEligible) {
            return allowance.tryClaimExact(1) ? remainingExecutions : 0;
        }
        return allowance.claimUpTo(remainingExecutions);
    }

    @Nullable
    private Recipe<?> recipeForStep(CraftingResolver.ResolutionStep step) {
        if (server == null) return null;
        Recipe<?> recipe = server.getRecipeManager().byKey(step.recipeId()).orElse(null);
        if (recipe == null) recipe = FluidContainerCatalog.byId(step.recipeId());
        if (recipe == null && step.modType().id().startsWith("irons_spellbooks")) {
            recipe = IronSpellBooksRecipeCatalog.byId(server.overworld(), step.recipeId());
        }
        if (recipe == null && "pmmo_salvage".equals(step.modType().id())) {
            recipe = PmmoSalvageCatalog.byId(step.recipeId());
        }
        if (recipe == null && "rs_integration".equals(step.recipeId().getNamespace())
                && step.recipeId().getPath().startsWith("vanilla_brewing/")) {
            recipe = VanillaBrewingCatalog.byId(step.recipeId());
        }
        return recipe;
    }

    private boolean requiresTargetedProduction(CraftingResolver.ResolutionStep step) {
        if (step.productionTarget() == null) return false;
        Recipe<?> recipe = recipeForStep(step);
        ModRecipeHandler handler = recipe == null ? null : ModRecipeHandlers.claimFor(recipe).winner();
        if (handler == null) return false;
        ProductionTarget target = step.productionTarget();
        if (target.resource() != null) {
            return handler.requiresTargetedProduction(
                    recipe, target.resource());
        }
        return target.material() != null
                && handler.requiresTargetedProduction(
                recipe, target.material().toStack(1));
    }

    @Nullable
    private ProductionAttemptState attachGraphAttempt(
            CraftNodeRuntime runtime, NodeId nodeId,
            CraftingResolver.ResolutionStep step) {
        CraftNode node = graphNodes.get(nodeId);
        if (node == null) return null;
        if (!requiresTargetedProduction(step) || step.productionTarget() == null) {
            runtime.attachOutputs(new NodeOutputAccumulator(node.outputs()));
            return null;
        }
        OutputPortId targetPort = node.outputs().stream()
                .filter(output -> output.kind() == OutputKind.PRIMARY
                        || output.kind() == OutputKind.DYNAMIC)
                .map(OutputDeclaration::id)
                .findFirst().orElse(null);
        if (targetPort == null) {
            runtime.attachOutputs(new NodeOutputAccumulator(node.outputs()));
            return null;
        }
        ProductionAttemptState state = productionAttempts.computeIfAbsent(nodeId,
                ignored -> new ProductionAttemptState(
                        new NodeOutputAccumulator(node.outputs()), targetPort,
                        step.productionTarget().quantity(),
                        CraftExecutionPolicy.probabilityAttemptLimit(step)));
        runtime.attachOutputs(state.outputs);
        runtime.targetCompletion(state.targetPort, state.targetQuantity);
        return state;
    }

    private boolean ensureRetryMaterials(NodeId nodeId, ServerPlayer online) {
        ProductionAttemptState attempt = productionAttempts.get(nodeId);
        List<MaterialBroker.Request> requests = graphRequests.getOrDefault(nodeId, List.of());
        if (attempt == null || attempt.attempts <= 0 || graphMaterials == null
                || requests.isEmpty()) {
            return true;
        }
        List<MaterialBroker.Request> shortages = graphMaterials.shortages(requests);
        if (shortages.isEmpty()) return true;
        if (shortages.stream().anyMatch(request ->
                !(request.source() instanceof MaterialSource.InitialPool))) {
            return false;
        }

        Map<MaterialKey, Long> needed = new LinkedHashMap<>();
        for (MaterialBroker.Request request : shortages) {
            needed.merge(request.material(), request.quantity(), SaturatingLongMath::add);
        }
        Map<StackKey, Long> available =
                new LinkedHashMap<>(MaterialSources.listAllAvailable(online, storageEndpoint));
        for (ItemStack stack : virtualInventory) {
            if (stack == null || stack.isEmpty()) continue;
            available.merge(StackKey.of(stack, true), (long) stack.getCount(),
                    SaturatingLongMath::add);
        }
        Map<MaterialSource, Long> published = new LinkedHashMap<>();
        for (Map.Entry<MaterialKey, Long> entry : needed.entrySet()) {
            long physicallyAvailable = available.entrySet().stream()
                    .filter(candidate -> MaterialMatcher.matchesExact(
                            entry.getKey(), candidate.getKey().toStack()))
                    .mapToLong(Map.Entry::getValue)
                    .reduce(0L, SaturatingLongMath::add);
            List<MaterialBroker.Request> materialShortages = shortages.stream()
                    .filter(request -> request.material().equals(entry.getKey()))
                    .toList();
            long represented = materialShortages.stream()
                    .map(MaterialBroker.Request::source)
                    .distinct()
                    .mapToLong(source -> graphMaterials.outstanding(source, entry.getKey()))
                    .reduce(0L, SaturatingLongMath::add);
            long free = Math.max(0L, physicallyAvailable - represented);
            for (MaterialBroker.Request request : materialShortages) {
                long alreadyPlanned = published.getOrDefault(request.source(), 0L);
                long quantity = Math.min(request.quantity(),
                        Math.max(0L, free - alreadyPlanned));
                if (quantity <= 0L) continue;
                graphMaterials.publish(request.source(), request.material(), quantity);
                published.merge(request.source(), quantity, SaturatingLongMath::add);
            }
        }
        return graphMaterials.shortages(requests).isEmpty();
    }

    private int nextGraphOperationSequence(NodeId nodeId) {
        ProductionAttemptState attempt = productionAttempts.get(nodeId);
        return attempt == null ? 0 : attempt.attempts;
    }

    private UUID publicOperationId(OperationId operationId) {
        String identity = operationId.craftId()
                + "|" + operationId.nodeId().value()
                + "|" + operationId.sequence();
        return UUID.nameUUIDFromBytes(
                identity.getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    private ProbabilisticProduction probabilityTarget(CraftingResolver.ResolutionStep step, ServerPlayer player) {
        ProbabilisticProduction existing = probabilityTargets.get(step);
        if (existing != null || !requiresTargetedProduction(step)) return existing;
        int multiplier = 16;
        int maximum = 4096;
        try {
            multiplier = RSIntegrationConfig.CRAFTING_PROBABILISTIC_ATTEMPT_MULTIPLIER.get();
            maximum = RSIntegrationConfig.CRAFTING_PROBABILISTIC_MAX_ATTEMPTS.get();
        } catch (RuntimeException ignored) {}
        int limit = (int) Math.min(maximum, (long) step.executions() * multiplier);
        ProbabilisticProduction progress = new ProbabilisticProduction(step.productionTarget(), Math.max(1, limit));
        probabilityTargets.put(step, progress);
        player.sendSystemMessage(Component.translatable("rsi.async.probability.started",
                step.productionTarget().displayStack().getHoverName(),
                step.productionTarget().quantity(), limit));
        return progress;
    }

    /** 缺少下一次投入时插入原料子计划；已有真实产物始终留在本任务账本中。 */
    private boolean prepareProbabilityInputs(CraftingResolver.ResolutionStep step,
                                              ProbabilisticProduction progress, ServerPlayer player) {
        Recipe<?> recipe = recipeForStep(step);
        ModRecipeHandler handler = recipe == null ? null : ModRecipeHandlers.claimFor(recipe).winner();
        List<IngredientSpec> inputs = handler == null ? null : handler.getIngredients(recipe);
        if (inputs == null || inputs.isEmpty()) {
            abort("Probabilistic recipe has no material contract: " + step.recipeId(),
                    Component.translatable("rsi.async.probability.materials", progress.remaining()));
            return false;
        }
        inputs = MaterialLocks.narrowSpecs(step.recipeId(), inputs, supplementalMaterialLocks);
        MaterialSources.invalidateFor(player, storageEndpoint);
        Map<StackKey, Long> available =
                new LinkedHashMap<>(MaterialSources.listAllAvailable(player, storageEndpoint));
        for (ItemStack stack : virtualInventory) {
            available.merge(StackKey.of(stack, true), (long) stack.getCount(),
                    MaterialSources::saturatedAdd);
        }
        // 先保护后续步骤的投入，也保护已经收集的当前目标，补做不能把它们再拆掉。
        List<IngredientSpec> protectedInputs = new ArrayList<>();
        Set<ResourceLocation> excluded = new HashSet<>();
        for (int i = currentStepIdx; i < steps.size(); i++) {
            CraftingResolver.ResolutionStep pending = steps.get(i);
            excluded.add(pending.recipeId());
            if (i == currentStepIdx) continue;
            Recipe<?> pendingRecipe = recipeForStep(pending);
            if (pendingRecipe == null) continue;
            List<IngredientSpec> pendingInputs = CraftPacketUtils.extractIngredientSpecs(pendingRecipe);
            if (pendingInputs == null) continue;
            for (IngredientSpec input : MaterialLocks.narrowSpecs(pending.recipeId(),
                    pendingInputs, supplementalMaterialLocks)) {
                if (!input.isEmpty()) protectedInputs.add(new IngredientSpec(
                        input.ingredient(),
                        CraftPacketUtils.requiredCount(input, pending.executions()),
                        input.consumption()));
            }
        }
        Map<StackKey, Long> usable =
                SupplementalMaterialPlan.afterProtecting(available, protectedInputs);
        SupplementalMaterialPlan.protectProduced(available, usable, progress.target(), progress.produced());
        // 材料已齐时直接开始下一试，避免每次轮询重建递归图。
        if (SupplementalMaterialPlan.hasInputs(usable, inputs)) return true;
        List<String> missing = new ArrayList<>();
        CraftPlanGraph supplemental;
        try {
            supplemental = CraftingResolver.resolveSupplementalGraph(inputs, usable,
                    player.serverLevel(), player, storageEndpoint, missing, excluded,
                    supplementalForcedRecipes, supplementalMaterialLocks,
                    new CraftingResolver.ActiveRootRecipe(
                            step.recipeId(),
                            progress.target().displayStack()));
        } catch (CraftingPlanningTimeoutException timeout) {
            abort("Probabilistic supplemental planning timed out: " + step.recipeId(),
                    Component.translatable("rsi.async.probability.planning_timeout", progress.remaining()));
            return false;
        }
        if (!missing.isEmpty() || supplemental.rootDemands().stream().anyMatch(root -> root.unresolvedQuantity() > 0)) {
            abort("Probabilistic supplemental materials unavailable: " + step.recipeId(),
                    Component.translatable("rsi.async.probability.materials", progress.remaining()));
            return false;
        }
        List<CraftingResolver.ResolutionStep> additional = ExecutionEquivalence.projectFlatSteps(supplemental);
        if (additional.isEmpty()) return true;
        if ((long) steps.size() + additional.size() > RSIntegrationConfig.CRAFTING_MAX_STEPS.get()) {
            abort("Probabilistic supplemental step limit reached", Component.translatable(
                    "rsi.async.probability.materials", progress.remaining()));
            return false;
        }
        steps.addAll(currentStepIdx, additional);
        stepRemaining = 0;
        RSIntegrationMod.LOGGER.info(ctx.format(
                "Supplemental probability inputs: recipe={} attempts={} produced={} target={} addedSteps={}"),
                step.recipeId(), progress.attempts(), progress.produced(), progress.target().quantity(), additional.size());
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
        // 大地之心污染虽然不是 GENERIC 普通批处理，但也不能交给
        // settleReadyVanillaNodes 的批处理路径；把它交给图执行器，
        // 由 startAdmittedGraphNode 的同步污染分支处理。
        return step.modType() != ModType.GENERIC
                || isEarthHeartTaintStep(step);
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
        var recipeCapability = ExternalIntegrationRuntime
                .isExternalType(step.modType())
                ? ExternalIntegrationRuntime.planningConcurrency(
                recipe, server.overworld().registryAccess())
                : GraphConcurrencyEligibility.capabilities(
                new GraphConcurrencyEligibility.Context(
                        step.modType().id(),
                        recipeClass,
                        // 已猜出代码的 Embers 配方不再按“推断中”处理，可正常并行。
                        step.inferMode() && !embersCodeKnown(step)));
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

        static PreparationResult waiting(String detail, @Nullable Component userMessage) {
            return new PreparationResult(PreparationState.RETRY, null, detail, userMessage);
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
        if (retryUntil != null && currentTick < retryUntil && !wakeOnMachineRelease(nodeId)) {
            return ConcurrentNodeExecutor.StartResult.retry();
        }
        if (graph != null && !CraftPlanningRevision.isCurrent(graph.planningRevision())) {
            graphFailureDetails.put(nodeId, "Crafting plan is stale after recipe or matcher reload");
            graphFailureCodes.put(nodeId, CraftFailureCode.CONTRACT_INCOMPATIBLE);
            return ConcurrentNodeExecutor.StartResult.failed();
        }
        CraftingResolver.ResolutionStep step = graphStep(nodeId);

        // Earth Heart 是同步步骤，但它的 modType 是 GENERIC；必须在通用节点
        // 拒绝逻辑之前识别，否则图执行器会把它当成“同步 generic 节点”直接失败，
        // 导致大地之心永远不会被放进玩家背包。
        if (isEarthHeartTaintStep(step)) {
            NodeAdmissionCoordinator.Candidate candidate = new NodeAdmissionCoordinator.Candidate(
                    nodeId, graphRequests.getOrDefault(nodeId, List.of()));
            NodeAdmissionCoordinator.Admission admission = graphAdmissions.tryAdmitClaimed(candidate);
            if (admission == null) {
                return graphRetry(nodeId, "material admission unavailable",
                        candidate.materialRequests());
            }
            // 图模式下上游产物通常保存在 MaterialBroker 的 producer 资产里，
            // 不会自动进入 virtualInventory；必须用本节点的预留取出大地之心。
            taintHeartQueue.clear();
            if (graphMaterials != null) {
                MaterialBroker.Checkout checkout = graphMaterials.checkout(admission.materialToken());
                List<ItemStack> reservedHearts = new ArrayList<>();
                reservedHearts.addAll(checkout.initialStacks());
                reservedHearts.addAll(checkout.producerStacks());
                for (ItemStack stack : reservedHearts) {
                    if (!isPlainEarthHeart(stack)) continue;
                    int take = Math.min(stack.getCount(),
                            Math.max(0, step.executions() - taintHeartQueue.size()));
                    for (int i = 0; i < take; i++) {
                        taintHeartQueue.add(stack.copyWithCount(1));
                    }
                }
            }
            if (!startEarthHeartTaint(online, step.executions(), nodeId)) {
                graphAdmissions.releaseMaterial(admission);
                return ConcurrentNodeExecutor.StartResult.failed();
            }
            clearGraphRetry(nodeId);
            graphAdmissions.commit(admission);
            graphAdmissions.settleMaterial(admission);
            // 污染是异步等待玩家背包里的物品被转化，不能立即标记完成；
            // 由 worker 在状态机完成后返回 SUCCEEDED，再发布产物。
            return ConcurrentNodeExecutor.StartResult.started(new EarthHeartTaintWorker());
        }

        if (step.modType() == ModType.GENERIC) {
            graphFailureDetails.put(nodeId,
                    "synchronous generic node reached concurrent graph dispatcher");
            graphFailureCodes.put(nodeId, CraftFailureCode.INTERNAL_ERROR);
            return ConcurrentNodeExecutor.StartResult.failed();
        }

        PreparationResult preparation = prepareGraphNode(nodeId, step, online);
        if (preparation.state() == PreparationState.RETRY) {
            machineWaitNotifier.notifyWait(online, preparation.userMessage());
            return graphRetry(nodeId, preparation.detail(),
                    graphRequests.getOrDefault(nodeId, List.of()));
        }
        if (preparation.state() == PreparationState.FATAL || preparation.prepared() == null) {
            graphFailureDetails.put(nodeId, preparation.detail());
            graphFailureCodes.put(nodeId, CraftFailureCode.CONTRACT_INCOMPATIBLE);
            RSIntegrationMod.LOGGER.warn(ctx.format("Graph node {} preparation failed: {}"),
                    nodeId, preparation.detail());
            if (preparation.userMessage() != null) {
                online.sendSystemMessage(preparation.userMessage());
            }
            return ConcurrentNodeExecutor.StartResult.failed();
        }
        PreparedGraphNode prepared = preparation.prepared();
        if (!ensureRetryMaterials(nodeId, online)) {
            releasePreparationQuietly(prepared.delegate());
            return graphRetry(nodeId, "retry materials unavailable",
                    graphRequests.getOrDefault(nodeId, List.of()));
        }
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
            graphFailureCodes.put(nodeId, CraftFailureCode.START_REJECTED);
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
        if (isMachineWait(detail) != graphMachineWaits.contains(nodeId)) {
            graphRetrySinceTick.remove(nodeId);
            graphRetryAttempts.remove(nodeId);
        }
        int attempts = Math.min(graphRetryAttempts.merge(nodeId, 1, Integer::sum), 6);
        int now = server.getTickCount();
        int since = graphRetrySinceTick.computeIfAbsent(nodeId, key -> now);
        // 正常机器排队不使用固定总时长；无进展仍由链级看门狗兜底。
        if (shouldAbortGraphRetry(detail, now - since)) {
            RSIntegrationMod.LOGGER.warn(ctx.format(
                    "Graph node {} stalled for {} ticks, aborting: {}"),
                    nodeId, now - since, detail);
            graphFailureDetails.put(nodeId, detail);
            graphFailureCodes.put(nodeId, CraftFailureCode.START_REJECTED);
            clearGraphRetry(nodeId);
            return ConcurrentNodeExecutor.StartResult.failed();
        }
        int delay = retryDelayTicks(detail, attempts);
        graphRetryUntilTick.put(nodeId, now + delay);
        if (isMachineWait(detail)) {
            graphMachineWaits.add(nodeId);
        } else {
            graphMachineWaits.remove(nodeId);
            graphWaitingMachines.remove(nodeId);
        }
        logGraphRetry(nodeId, detail, requests);
        notifySlowRetry(nodeId, detail);
        return ConcurrentNodeExecutor.StartResult.retry();
    }

    /** 机器类等待用短退避，材料类等待保留长退避，避免把“等材料”变成忙轮询。 */
    static int retryDelayTicks(String detail, int attempts) {
        int cap = isMachineWait(detail)
                ? GRAPH_RETRY_MACHINE_BUSY_MAX_DELAY_TICKS
                : GRAPH_RETRY_MAX_DELAY_TICKS;
        int bounded = Math.max(1, Math.min(attempts, 6));
        return Math.min(cap, 1 << bounded);
    }

    static boolean isMachineWait(String detail) {
        return detail != null && MACHINE_WAIT_DETAILS.contains(detail);
    }

    static boolean shouldAbortGraphRetry(String detail, int waitedTicks) {
        return !isMachineWait(detail) && waitedTicks > GRAPH_RETRY_ABORT_TICKS;
    }

    static boolean hasLeasedMachine(List<MachineLeaseRegistry.MachineKey> machines,
                                    MachineLeaseRegistry leases) {
        return machines.stream().anyMatch(leases::isLeased);
    }

    static boolean machineLeaseReleased(List<MachineLeaseRegistry.MachineKey> machines,
                                        MachineLeaseRegistry leases) {
        return machines.stream().anyMatch(machine -> !leases.isLeased(machine));
    }

    private List<MachineLeaseRegistry.MachineKey> leasedMachineKeys(
            List<BoundMachine> machines, String logicalType) {
        return machines.stream()
                .map(machine -> new MachineLeaseRegistry.MachineKey(
                        machine.dim(), machine.pos(), logicalType))
                .filter(machineLeases::isLeased)
                .toList();
    }

    /** 相关机器真正空闲才提前结束退避，无关租约释放不会唤醒本节点。 */
    private boolean wakeOnMachineRelease(NodeId nodeId) {
        if (!graphMachineWaits.contains(nodeId)) return false;
        return machineLeaseReleased(graphWaitingMachines.getOrDefault(nodeId, List.of()), machineLeases);
    }

    private void clearGraphRetry(NodeId nodeId) {
        graphRetryUntilTick.remove(nodeId);
        graphRetryAttempts.remove(nodeId);
        graphRetrySinceTick.remove(nodeId);
        graphMachineWaits.remove(nodeId);
        graphWaitingMachines.remove(nodeId);
    }

    /**
     * 同一步骤长时间停在同一个等待原因时，把原因告诉玩家。
     * 只提示一次：真正的等待可能很久（机器正在跑别的配方），但玩家至少要能知道在等什么。
     */
    private void notifySlowRetry(NodeId nodeId, String detail) {
        if (graphRetryAttempts.getOrDefault(nodeId, 0) < GRAPH_RETRY_NOTIFY_ATTEMPTS) return;
        // 按原因去重：同一条链上常有十几个节点在等同一台机器，
        // 逐节点提示会让聊天栏出现十几行一模一样的话。
        if (!graphRetryNotified.add(detail)) return;
        ServerPlayer online = resolvePlayer();
        if (online == null) return;
        online.sendSystemMessage(Component.translatable("rsi.async.wait.slow", detail));
    }

    private void logGraphRetry(NodeId nodeId, String detail,
                               List<MaterialBroker.Request> requests) {
        String key = craftId + ":" + detail;
        int count = graphRetryCounts.merge(key, 1, Integer::sum);
        if (GRAPH_RETRY_LOGS.allow(key)) {
            // 前两次重试按 INFO 输出：现场只有 INFO 级日志，静默重试会让玩家只看到“准备中”。
            if (count <= 2) {
                RSIntegrationMod.LOGGER.info(ctx.format(
                        "[RSI-GraphRetry] node={} detail={} attemptsSinceLog={} requests={}"),
                        nodeId, detail, count, requests);
            } else {
                RSIntegrationMod.LOGGER.debug(ctx.format(
                        "[RSI-GraphRetry] node={} detail={} attemptsSinceLog={} requests={}"),
                        nodeId, detail, count, requests);
            }
            graphRetryCounts.put(key, 0);
        }
    }

    static boolean validatePreparedDelegate(
            IBatchDelegate delegate, ServerPlayer player, ResourceLocation recipeId,
            @Nullable ResourceLocation dimension, BlockPos position) {
        return prepareDelegate(delegate, player, recipeId, dimension, position)
                .state() == IBatchDelegate.PreparationState.READY;
    }

    static IBatchDelegate.PreparationResult prepareDelegate(
            IBatchDelegate delegate, ServerPlayer player, ResourceLocation recipeId,
            @Nullable ResourceLocation dimension, BlockPos position) {
        IBatchDelegate.PreparationResult result;
        try {
            result = PreparationMessageScope.prepare(
                    delegate, player, recipeId, dimension, position);
        } catch (RuntimeException exception) {
            releasePreparationQuietly(delegate);
            throw exception;
        }
        if (result.state() != IBatchDelegate.PreparationState.READY) {
            releasePreparationQuietly(delegate);
        }
        return result;
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

    @Nullable
    private Recipe<?> recipeForDiagnostics(@Nullable ResourceLocation recipeId) {
        if (server == null || recipeId == null) return null;
        return server.getRecipeManager().byKey(recipeId).orElse(null);
    }

    private PreparationResult prepareGraphNode(NodeId nodeId,
                                               CraftingResolver.ResolutionStep step,
                                               ServerPlayer online) {
        List<BoundMachine> machines = MachineDispatchPlanner.deduplicate(
                AltarBindingRegistry.getBoundMachinesForRecipe(
                online, step.modType(), step.recipeId()));
        int boundCount = machines.size();
        machines.sort((a, b) -> {
            ResourceLocation playerDim = online.level().dimension().location();
            boolean aSame = a.dim().equals(playerDim);
            boolean bSame = b.dim().equals(playerDim);
            if (aSame != bSame) return aSame ? -1 : 1;
            return IronFurnaceMachinePriority.compare(a, b);
        });
        machines = applyMachineSelection(machines, step.recipeId());
        graphWaitingMachines.put(nodeId, leasedMachineKeys(machines, step.modType().id()));
        IBatchDelegate delegate = createStepDelegate(step);
        if (delegate == null) {
            return PreparationResult.fatal("No delegate for mod type " + step.modType().id());
        }
        // Generic and virtual delegates are positionless. Physical adapters may
        // reuse the generic material transaction logic while still requiring a
        // bound machine, so the virtual ModType check must stay explicit.
        if (delegate.getClass() == GenericBatchDelegate.class || step.modType().isVirtual()) {
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
                        abd.setTargetResource(
                                IntegrationResourceKey.item(nodeTarget));
                    } else if (targetOutput != null && isGraphTerminalNode(nodeId)) {
                        abd.setTargetOutput(targetOutput);
                        abd.setTargetResource(targetResource);
                    } else if (isGraphTerminalNode(nodeId)
                            && targetResource != null) {
                        abd.setTargetResource(targetResource);
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
            // 诊断：逐层报告绑定过滤结果，便于从日志直接定位是哪一步清空了候选机器。
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Binding] no machine recipe={} type={} subType={} bound={} afterSelection={} diagnostics={}",
                    step.recipeId(), step.modType().id(),
                    AltarBindingRegistry.recipeSubTypeHint(step.recipeId()),
                    boundCount, machines.size(),
                    AltarBindingRegistry.diagnoseMachines(online, step.modType(),
                            step.recipeId(), recipeForDiagnostics(step.recipeId())));
            return PreparationResult.fatal("No bound machine matches " + step.recipeId()
                    + " (type=" + step.modType().id() + ")");
        }
        List<BoundMachine> available = LoadBalancer.filterAvailable(machines, server, delegate);
        available = MachineDispatchPlanner.filterUnleased(
                available, machineLeases, step.modType().id());
        if (available.isEmpty()) {
            return PreparationResult.retry(MACHINE_BUSY_DETAIL);
        }
        List<BoundMachine> eligible = new ArrayList<>();
        releasePreparationQuietly(delegate);
        delegate = null;
        boolean validationThrew = false;
        boolean retryableRejection = false;
        boolean protectionRejection = false;
        String fatalDetail = "";
        String firstRetryDetail = "";
        Component fatalUserMessage = null;
        Component retryUserMessage = null;
        for (BoundMachine machine : available) {
            IBatchDelegate candidate = null;
            boolean retained = false;
            try {
                ResourceKey<Level> dimKey = ResourceKey.create(
                        Registries.DIMENSION, machine.dim());
                ServerLevel machineLevel = server.getLevel(dimKey);
                if (machineLevel == null
                        || !ProtectionChecker.canInteract(online, machineLevel, machine.pos())) {
                    protectionRejection = true;
                    continue;
                }
                candidate = createStepDelegate(step, machine.type());
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
                            abd.setTargetResource(
                                    IntegrationResourceKey.item(nodeTarget));
                        } else if (targetOutput != null && isGraphTerminalNode(nodeId)) {
                            abd.setTargetOutput(targetOutput);
                            abd.setTargetResource(targetResource);
                        } else if (isGraphTerminalNode(nodeId)
                                && targetResource != null) {
                            abd.setTargetResource(targetResource);
                        }
                    }
                    if (eligible.isEmpty()) {
                        delegate = candidate;
                        retained = true;
                    }
                    eligible.add(machine);
                } else if (result.state() == IBatchDelegate.PreparationState.RETRY) {
                    retryableRejection = true;
                    if (firstRetryDetail.isEmpty() && result.detail() != null) {
                        firstRetryDetail = result.detail();
                    }
                    if (retryUserMessage == null) {
                        retryUserMessage = result.userMessage();
                    }
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
            String retryDetail = validationThrew
                    ? "delegate validation temporarily failed on every available machine"
                    : !firstRetryDetail.isEmpty()
                            ? firstRetryDetail
                            : "no available machine currently accepts the recipe";
            return retryUserMessage == null
                    ? PreparationResult.retry(retryDetail)
                    : PreparationResult.waiting(retryDetail, retryUserMessage);
        }
        int desiredOperations = Math.max(1, step.executions());
        // 配置里的并发上限已经移除，剩余操作数就是可用容量；真正的并行度
        // 由可用机器数、材料复用上限和 delegate 契约共同决定。
        int availableOperations = desiredOperations;
        boolean concurrencySafe = !concurrencyDecision(step, delegate).exclusive();
        // 输入缓冲只能让“一台”机器整批吞下操作；只要存在多台可并行机器，
        // 就应拆成并行组，让每台机器各自缓冲自己的份额，而不是把 11 个
        // 操作全部塞进第一台熔炉。
        boolean bufferedNode = canBufferEntireGraphNode(delegate, desiredOperations)
                && (eligible.size() <= 1 || !concurrencySafe);
        boolean operationGroup = shouldUseGraphOperationGroup(desiredOperations, availableOperations)
                && !bufferedNode && concurrencySafe;
        // 不能并行不是错误：退回单 worker 顺序执行，绝不能让节点无限重试。
        MaterialPlan delegateMaterials = materialPlanFor(delegate);
        boolean workerReusable = !delegateMaterials.reusableEntries().isEmpty();
        int operationCost = operationGroup
                ? graphOperationWorkerCount(desiredOperations, availableOperations,
                        eligible.size(), concurrencySafe, workerReusable)
                : 1;
        if (operationGroup && workerReusable) {
            operationCost = Math.min(operationCost, Math.max(1,
                    reusableWorkerCapacity(delegateMaterials.legacyGraphSpecs(),
                            online, storageEndpoint)));
        }
        RSIntegrationMod.debug(
                "[RSI-GraphConcurrency] node={} machines={} eligible={} operations={} buffered={} safe={} workers={} reusable={}",
                step.recipeId(), machines.size(), eligible.size(), desiredOperations,
                bufferedNode, concurrencySafe, operationCost, workerReusable);
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
                .map(output -> ProductionTarget.of(
                                output.resource(),
                                Math.max(
                                        1L,
                                        output.quantity()
                                                / Math.max(
                                                1,
                                                node.executions()
                                        )
                                )
                        )
                        .displayStack())
                .orElse(ItemStack.EMPTY);
    }

    static boolean shouldUseGraphOperationGroup(int executions, int availableOperations) {
        return executions >= 2 && availableOperations >= 1;
    }

    static boolean requiresGraphOperationSlot(int executions, boolean bufferedNode,
                                              boolean operationGroup) {
        return executions > 1 && !bufferedNode && !operationGroup;
    }

    static boolean canBufferEntireGraphNode(IBatchDelegate delegate, int executions) {
        if (delegate == null || executions <= 1 || !delegate.supportsInputBuffer()) return false;
        InputBufferPlan plan = delegate.inputBufferPlan(executions);
        return plan != null && plan.enabled() && plan.operations() == executions;
    }

    static int graphOperationWorkerCount(int executions, int availableOperations,
                                         int eligibleMachines, boolean concurrencySafe,
                                         boolean workerReusable) {
        if (!shouldUseGraphOperationGroup(executions, availableOperations)
                || !concurrencySafe) return 1;
        return Math.max(1, Math.min(Math.min(executions, availableOperations), eligibleMachines));
    }

    /** Reads the structured contract, treating a null legacy override as an empty plan. */
    static MaterialPlan materialPlanFor(@Nullable IBatchDelegate delegate) {
        if (delegate == null) return MaterialPlan.none();
        MaterialPlan plan = delegate.materialPlan();
        return plan == null ? MaterialPlan.none() : plan;
    }

    /**
     * 耐久输入的物理件数依赖运行时 Damage NBT，规划阶段已经算出精确数量。
     * 返回 null 表示该节点没有耐久输入，继续使用 delegate 的普通缩放路径。
     */
    @Nullable
    private List<IngredientSpec> plannedDurabilityGraphSpecs(NodeId nodeId) {
        CraftNode node = graphNodes.get(nodeId);
        return node == null ? null : plannedDurabilityGraphSpecs(node.inputs());
    }

    @Nullable
    static List<IngredientSpec> plannedDurabilityGraphSpecs(List<InputDemand> inputs) {
        if (inputs == null || inputs.isEmpty()
                || inputs.stream().noneMatch(input ->
                input.consumption().kind() == InputConsumption.Kind.DURABILITY)) {
            return null;
        }
        List<IngredientSpec> specs = new ArrayList<>(inputs.size());
        for (InputDemand input : inputs) {
            specs.add(new IngredientSpec(
                    input.ingredient(),
                    SaturatingLongMath.clampToInt(input.quantity()),
                    input.consumption()));
        }
        return List.copyOf(specs);
    }

    /**
     * Reusable materials are required once per physical worker.  The graph
     * planner intentionally records only one catalyst demand, so use the live
     * RS/player snapshot to avoid selecting more workers than can be equipped.
     * A missing snapshot is treated conservatively as one worker; the broker
     * admission still remains authoritative for planned producer outputs.
     */
    static int reusableWorkerCapacity(List<IngredientSpec> specs,
                                      ServerPlayer online, @Nullable CraftStorageEndpoint endpoint) {
        if (specs == null || specs.isEmpty() || online == null) return 1;
        return reusableWorkerCapacity(specs,
                MaterialSources.listAllAvailable(online, endpoint));
    }

    static int reusableWorkerCapacity(List<IngredientSpec> specs,
                                      Map<CraftingResolver.StackKey, ? extends Number> supplied) {
        if (specs == null || specs.isEmpty()) return 1;
        Map<CraftingResolver.StackKey, Long> available = new LinkedHashMap<>();
        supplied.forEach((key, count) -> {
            if (key != null && count != null && count.longValue() > 0L) {
                available.put(key, count.longValue());
            }
        });
        int capacity = Integer.MAX_VALUE;
        boolean found = false;
        for (int i = 0; i < specs.size(); i++) {
            IngredientSpec spec = specs.get(i);
            if (spec.isEmpty()) continue;
            if (!spec.consumption().reusableWithinWorkerBatch()) continue;
            found = true;
            long matching = 0L;
            for (Map.Entry<CraftingResolver.StackKey, Long> entry : available.entrySet()) {
                if (IngredientMatcher.test(spec.ingredient(), entry.getKey())) {
                    matching = MaterialSources.saturatedAdd(matching, entry.getValue());
                }
            }
            capacity = Math.min(capacity,
                    SaturatingLongMath.clampToInt(matching / Math.max(1, spec.count())));
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
        OperationId operationIdentity = null;
        boolean ownershipTransferred = false;
        boolean terminalCleanupInvoked = false;
        try {
            delegate.configureMaterialReservation(nodeLedger, online);
            delegate.prepareGraphBatch(Math.max(1, prepared.step().executions()));
            delegate.prepareOperationCount(Math.max(1, prepared.step().executions()));
            Component outputStorageFailure = delegate.validateOutputStorage(online);
            if (outputStorageFailure != null) {
                online.sendSystemMessage(outputStorageFailure);
                return GraphDispatchResult.fatal("output storage preflight rejected");
            }
            if (prepared.parallelGroup()) {
                List<BoundMachine> workers = new ArrayList<>(
                        prepared.machines().subList(0, prepared.operationCost()));
                // 原 delegate 已经在 prepareGraphNode 里完成过 prepare；
                // 若机器带独占锁（如 Embers 炼金台），必须先释放它，
                // 否则新建的 worker 子 delegate 会拿不到同一把锁，永远 RETRY。
                releasePreparationQuietly(delegate);
                var groupCapability = concurrencyDecision(prepared.step(), delegate).capabilities();
                ParallelCraftGroup group = new ParallelCraftGroup(workers,
                        prepared.step().modType(), prepared.step().recipeId(), online,
                        prepared.step().executions(), prepared.step().inferMode(), groupCapability,
                        storageEndpoint);
                IBatchDelegate.PreparationResult groupPreparation = prepareDelegate(
                        group, online, prepared.step().recipeId(), null, BlockPos.ZERO);
                if (groupPreparation.state() == IBatchDelegate.PreparationState.READY) {
                    delegate = group;
                    group.setMachineServer(server);
                    ItemStack nodeTarget = graphNodeTargetOutput(graphNodes.get(nodeId));
                    if (!nodeTarget.isEmpty()) {
                        group.setTargetOutput(nodeTarget);
                        group.setTargetResource(
                                IntegrationResourceKey.item(nodeTarget));
                    } else if (targetOutput != null && isGraphTerminalNode(nodeId)) {
                        group.setTargetOutput(targetOutput);
                        group.setTargetResource(targetResource);
                    } else if (isGraphTerminalNode(nodeId)
                            && targetResource != null) {
                        group.setTargetResource(targetResource);
                    }
                } else {
                    return GraphDispatchResult.retry(
                            "operation group could not prepare a worker for serial dispatch"
                                    + (groupPreparation.detail().isBlank()
                                    ? "" : ": " + groupPreparation.detail()));
                }
            }
            boolean parallelGroup = delegate instanceof ParallelCraftGroup;
            MaterialPlan materialPlan = parallelGroup ? MaterialPlan.none() : materialPlanFor(delegate);
            List<IngredientSpec> plannedDurabilitySpecs =
                    plannedDurabilityGraphSpecs(nodeId);
            if (plannedDurabilitySpecs != null
                    && plannedDurabilitySpecs.size() != materialPlan.entries().size()) {
                plannedDurabilitySpecs = null;
            }
            List<IngredientSpec> graphSpecs = parallelGroup
                    ? delegate.getGraphSpecs()
                    : plannedDurabilitySpecs != null
                    ? plannedDurabilitySpecs
                    : materialPlan.legacyGraphSpecs();
            GraphNodeMaterials reserved;
            if (delegate instanceof CrockPotBatchDelegate crockPot
                    && crockPot.usesPlannedCategoryMaterials()) {
                reserved = reservePlannedCheckoutMaterials(
                        online, nodeLedger, admission.materialToken());
            } else {
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
                        delegate, graphSpecs,
                        plannedDurabilitySpecs != null ? 1 : prepared.step().executions(),
                        online,
                        nodeLedger, admission.materialToken());
            }
            if (reserved == null) return GraphDispatchResult.retry("exact graph materials are temporarily unavailable");
            List<ItemStack> materials = reserved.materials();

            List<IngredientSpec> supplementalSpecs = parallelGroup
                    ? delegate.getSupplementalSpecs() : materialPlan.legacySupplementalSpecs();
            if (supplementalSpecs != null && !supplementalSpecs.isEmpty()) {
                List<ItemStack> supplementalMaterials = reserveSupplementalMaterials(
                        supplementalSpecs, online, nodeLedger);
                if (supplementalMaterials == null) {
                    return GraphDispatchResult.retry("supplemental materials unavailable");
                }
                materials = parallelGroup
                        ? delegate.mergeSupplementalMaterials(materials, supplementalMaterials)
                        : materialPlan.mergeLegacyReservations(materials, supplementalMaterials);
            }
            InputBufferPlan graphInputBuffer = canBufferEntireGraphNode(
                    delegate, prepared.step().executions())
                    ? delegate.inputBufferPlan(prepared.step().executions())
                    : InputBufferPlan.none();
            OperationStartContext startContext;
            if (delegate instanceof ParallelCraftGroup group) {
                List<IngredientSpec> operationSpecs = group.getFlatOperationMaterials();
                if (operationSpecs == null || operationSpecs.isEmpty()) {
                    return GraphDispatchResult.fatal(
                            "parallel group has no per-operation material layout");
                }
                startContext = OperationStartContext.repeatedChainReserved(
                        online, nodeLedger, group.operationMaterialPlan(),
                        prepared.step().executions(), operationSpecs.size(), materials);
            } else {
                startContext = OperationStartContext.chainReserved(
                        online, nodeLedger, materialPlan, materials, graphInputBuffer);
            }
            if (startContext.buffered()) {
                RSIntegrationMod.LOGGER.info(ctx.format(
                        "[RSI-Craft] graph buffered dispatch node={} recipe={} delegate={} operations={} inputs={} outputs={}"),
                        nodeId, prepared.step().recipeId(), delegate.getClass().getSimpleName(),
                        graphInputBuffer.operations(), graphInputBuffer.inputs().size(),
                        graphInputBuffer.outputs().size());
            }
            if (!delegate.validateExecutionContext(online)) {
                return GraphDispatchResult.fatal(
                        "execution context changed before material commit");
            }
            if (delegate instanceof ParallelCraftGroup group) {
                group.setDispatchAllowed(() -> dispatchAllowed.getAsBoolean());
                group.setMachineLeases(machineLeases);
                group.setReservationTokens(reserved.operationTokens());
                group.setReusableReservationTokens(reserved.reusableTokens());
                group.setVirtualDebits(reserved.virtualDebits());
                group.setProducerDebits(reserved.producerDebits());
                group.setOperationKernel(operationKernel, craftId, nodeId,
                        craftOperationBudget, nextGraphOperationSequence(nodeId));
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
                operationIdentity = new OperationId(
                        craftId, nodeId, nextGraphOperationSequence(nodeId));
                operationSession = operationKernel.tryPrepare(
                        operationIdentity,
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
            if (operationIdentity != null) {
                startContext = startContext.withOperationIdentity(
                        operationIdentity.craftId(),
                        publicOperationId(operationIdentity),
                        operationIdentity.sequence() + 1
                );
            }
            attachMagicReservation(delegate);

            CraftNodeRuntime runtime = new CraftNodeRuntime(nodeId,
                    prepared.step().recipeId().toString(), delegate, nodeLedger,
                    admission, operationSession);
            if (graphInputBuffer.enabled()) {
                runtime.setLogicalOperations(graphInputBuffer.operations());
            }
            runtime.setReusableReservationTokens(reserved.reusableTokens());
            ProductionAttemptState attempt =
                    attachGraphAttempt(runtime, nodeId, prepared.step());
            runtime.setChainContext(virtualInventory, online);

            graphAdmissions.commit(admission);
            runtime.markDispatched();
            nodeRuntimes.put(nodeId, runtime);
            ownershipTransferred = true;
            IBatchDelegate startDelegate = delegate;
            List<ItemStack> startMaterials = materials;
            OperationStartContext operationStartContext = startContext;
            RSIntegrationMod.LOGGER.debug(ctx.format(
                    "Graph node dispatching: node={} recipe={} delegate={} machine={} materials={}"),
                    nodeId, prepared.step().recipeId(), delegate.getClass().getSimpleName(),
                    prepared.machine().pos(), startMaterials.size());
            boolean accepted = operationSession != null
                    ? operationSession.tryStart(() ->
                            startDelegate.startOperation(operationStartContext))
                    : startDelegate.startOperation(operationStartContext);
            if (!accepted) {
                String startDetail = delegate.lastStartFailureDetail();
                RSIntegrationMod.LOGGER.warn(ctx.format(
                        "Graph node delegate rejected start: node={} recipe={} delegate={} machine={} detail={}"),
                        nodeId, prepared.step().recipeId(), delegate.getClass().getSimpleName(),
                        prepared.machine().pos(), startDetail == null ? "<none>" : startDetail);
                runtime.markStartFailed("delegate rejected graph dispatch after start attempt: delegate="
                        + delegate.getClass().getSimpleName()
                        + " recipe=" + prepared.step().recipeId()
                        + (startDetail == null || startDetail.isBlank() ? "" : " detail=" + startDetail));
            } else {
                if (attempt != null) attempt.started();
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
                new OperationId(craftId, nodeId, nextGraphOperationSequence(nodeId)),
                delegate.allowsOverlappingOutputCaptureOrigins());
        if (lease == null) {
            RSIntegrationMod.LOGGER.warn(ctx.format(
                    "Deferred graph output capture unavailable for node={} recipe={} machine={}"),
                    nodeId, delegate.getClass().getSimpleName(), machine.pos());
            return;
        }
        ResourceKey<Level> dimension = ResourceKey.create(
                Registries.DIMENSION, machine.dim());
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
                new OperationId(craftId, nodeId, nextGraphOperationSequence(nodeId)),
                craftOperationBudget, machineScope, null);
        if (operationSession == null) {
            return GraphDispatchResult.retry("operation budget or machine is temporarily unavailable");
        }
        if (!operationSession.commit(() -> nodeLedger.commitToEndpoint(storageEndpoint, online))) {
            operationSession.close();
            return GraphDispatchResult.retry("node ledger commit did not complete");
        }

        CraftNodeRuntime runtime = new CraftNodeRuntime(nodeId,
                prepared.step().recipeId().toString(), delegate, nodeLedger,
                admission, operationSession);
        ProductionAttemptState attempt =
                attachGraphAttempt(runtime, nodeId, prepared.step());
        runtime.setChainContext(virtualInventory, online);
        graphAdmissions.commit(admission);
        runtime.markDispatched();
        nodeRuntimes.put(nodeId, runtime);
        materializePrivateLedgerGraphInputs(admission, online);
        attachMagicReservation(delegate);

        boolean accepted = operationSession.tryStart(() -> delegate.startOperation(
                OperationStartContext.delegateExtracted(online, nodeLedger)));
        if (!accepted) runtime.markStartFailed("delegate rejected private-ledger graph dispatch");
        else if (attempt != null) attempt.started();
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
                PlayerUtils.safeGiveToStorage(online, leftover, storageEndpoint);
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
        if (runtime.consumeProgressSignal() || !publications.isEmpty()
                || runtime.isWaitingForLeasedMachine()) {
            graphProgressWatchdog.markProgress();
        }
        for (NodeOutputAccumulator.Publication publication : publications) {
            graphMaterials.publishActual(new MaterialSource.ProducerOutput(publication.port()),
                    publication.material(), publication.stack());
            if (publication.resource().kind()
                    == IntegrationResourceKey.ResourceKind.ITEM
                    && isPrimaryGraphPublication(
                    graphNodes.get(nodeId), publication)) {
                ExternalItemProgressBridge.enqueueCrafted(resolvePlayer(), publication.stack());
            }
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
        graphFailureCodes.put(nodeId, worker instanceof CraftNodeRuntime runtime
                ? runtime.failureCode() : CraftFailureCode.UNKNOWN);
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
                ProductionAttemptState attempt = productionAttempts.get(nodeId);
                if (attempt != null && attempt.canAttempt()) {
                    for (ItemStack stack : runtime.drainOutputSurplus()) {
                        addToVirtualInventory(stack);
                    }
                    nodeRuntimes.remove(nodeId);
                    snapshotCommittedVirtual();
                    graphProgressWatchdog.markProgress();
                    RSIntegrationMod.LOGGER.debug(ctx.format(
                            "Graph node {} retrying targeted production: attempts={}/{} published={}/{}"),
                            nodeId, attempt.attempts, attempt.maxAttempts,
                            attempt.outputs.publishedQuantity(attempt.targetPort),
                            attempt.targetQuantity);
                    return ConcurrentNodeExecutor.CompletionStatus.RETRY;
                }
                String detail = runtime.outputShortageDetail();
                runtime.markCompletionFailed(detail, CraftFailureCode.OUTPUT_MISSING);
                graphFailureDetails.put(nodeId, detail);
                graphFailureCodes.put(nodeId, CraftFailureCode.OUTPUT_MISSING);
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
            runtime.markCompletionFailed(detail, CraftFailureCode.INTERNAL_ERROR);
            graphFailureDetails.put(nodeId, detail);
            graphFailureCodes.put(nodeId, CraftFailureCode.INTERNAL_ERROR);
            RSIntegrationMod.LOGGER.error(ctx.format("Failed to settle graph node {}"), nodeId, exception);
            return ConcurrentNodeExecutor.CompletionStatus.FAILED;
        }
    }

    private void publishNodeOutputs(NodeId nodeId, List<ItemStack> actualOutputs) {
        if (!publishDeclaredNodeOutputs(nodeId,
                new ArrayList<>(StackCopies.excludingEmpty(actualOutputs)))) {
            throw new IllegalStateException("Graph node output did not satisfy its declarations: " + nodeId);
        }
    }

    /** Move exact runtime results into graph-owned lots after validating every declaration. */
    private boolean publishDeclaredNodeOutputs(NodeId nodeId, List<ItemStack> actualOutputs) {
        CraftNode node = graphNodes.get(nodeId);
        if (node == null || graphMaterials == null) return false;

        List<ItemStack> remaining = StackCopies.excludingEmpty(actualOutputs);
        Map<OutputDeclaration, List<ItemStack>> matched = new LinkedHashMap<>();
        List<String> checks = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        for (OutputDeclaration output : node.outputs()) {
            List<ItemStack> fragments = removeMatchingFragments(
                    remaining, output.material(), output.quantity());
            long actualCount = fragments.stream().mapToLong(ItemStack::getCount).sum();
            String check = describeOutputCheck(output, actualCount);
            checks.add(check);
            if (actualCount != output.quantity()) mismatches.add(check);
            matched.put(output, fragments);
        }
        if (!mismatches.isEmpty()) {
            // 所有声明都先核对完再报告，failed= 才是真实的失败个数，mismatches= 给出逐条明细。
            RSIntegrationMod.LOGGER.warn(ctx.format(
                            "[RSI-GraphOutputMismatch] node={} recipe={} modType={} "
                                    + "recipeType={} executions={} failed={} mismatches={} "
                                    + "checks={} declarations={} actual={} unmatched={}"),
                    node.id().value(), node.recipeId(), node.modTypeId(),
                    node.recipeTypeId(), node.executions(), mismatches.size(), mismatches,
                    checks, describeOutputDeclarations(node.outputs()),
                    describeStacksForLogging(actualOutputs),
                    describeStacksForLogging(remaining));
            for (ItemStack stack : actualOutputs) addToVirtualInventory(stack);
            actualOutputs.clear();
            return false;
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
            List<ItemStack> stacks, MaterialKey material, long limit) {
        long remaining = limit;
        List<ItemStack> removed = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (remaining <= 0) break;
            if (stack.isEmpty() || !MaterialMatcher.matchesOutputDeclaration(material, stack)) continue;
            int take = (int) Math.min(remaining, stack.getCount());
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
            IBatchDelegate delegate, List<IngredientSpec> specs,
            int executions, ServerPlayer online, ExtractionLedger ledger,
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
            List<Integer> reusableIndices =
                    reusableMaterialIndices(operationSpecs, operationSpecs.size());
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
                        material = ledger.reserve(spec.ingredient(), spec.count(),
                                storageEndpoint, online, null, null);
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
                List<ItemStack> producerBefore = StackCopies.excludingEmpty(producerPool);
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
                materials.addAll(StackCopies.includingEmpty(full));
                tokens.add(ledger.tokenSince(mark));
                virtualDebits.add(List.of());
                producerDebits.add(consumedFragments(producerBefore, producerPool));
            }
            releaseUnusedGraphMaterials(materialToken, initialPool, producerPool);
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
        List<IngredientSpec> scaledSpecs = scaleGraphSpecsForExecutions(specs, executions);
        List<Integer> reusableIndices = reusableMaterialIndices(specs, specs.size());
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
        releaseUnusedGraphMaterials(materialToken, initialPool, producerPool);
        return new GraphNodeMaterials(materials, List.of(), List.of(), List.of(), reusableTokens);
    }

    private void releaseUnusedGraphMaterials(
            MaterialBroker.ReservationToken materialToken,
            List<ItemStack> initialPool,
            List<ItemStack> producerPool) {
        if (graphMaterialPoolsDrained(initialPool, producerPool)) return;
        if (graphMaterials == null) {
            throw new IllegalStateException("graph materials unavailable for checkout reconciliation");
        }
        List<ItemStack> unusedInitial = StackCopies.excludingEmpty(initialPool);
        List<ItemStack> unusedProducer = StackCopies.excludingEmpty(producerPool);
        graphMaterials.releaseUnusedReservedFragments(
                materialToken, unusedInitial, unusedProducer);
        RSIntegrationMod.LOGGER.warn(ctx.format(
                "[RSI-Graph] Released unused planned materials node={} recipe={} "
                        + "initial={} producer={}"),
                currentStepIdx, currentRecipeForLogging(),
                describeStacksForLogging(unusedInitial),
                describeStacksForLogging(unusedProducer));
    }

    static boolean graphMaterialPoolsDrained(
            List<ItemStack> initialPool, List<ItemStack> producerPool) {
        return MaterialReservationPlanner.graphMaterialPoolsDrained(initialPool, producerPool);
    }

    static List<IngredientSpec> scaleGraphSpecsForExecutions(
            List<IngredientSpec> specs,
            int executions) {
        return MaterialReservationPlanner.scaleGraphSpecsForExecutions(specs, executions);
    }

    static List<Integer> reusableMaterialIndices(
            List<IngredientSpec> specs, int specCount) {
        return MaterialReservationPlanner.reusableMaterialIndices(specs, specCount);
    }

    private static List<ItemStack> consumedFragments(
            List<ItemStack> before, List<ItemStack> after) {
        return MaterialReservationPlanner.consumedFragments(before, after);
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
                if (!combined.isEmpty() && !MaterialMatcher.equivalentRuntimeFragment(combined, produced)) continue;
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
                } else if (MaterialMatcher.equivalentRuntimeFragment(combined, initial)) {
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
            if (remaining <= 0L) break;
            if (stack.isEmpty() || stack.getItem() != planned.getItem()) continue;
            int take = (int) Math.min(remaining, stack.getCount());
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
        return BuiltInRegistries.ITEM.getKey(stack.getItem())
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
            if (exactNbt && !MaterialMatcher.sameRuntimeFragment(selected, stack)) continue;
            int take = (int) Math.min(remaining, stack.getCount());
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
            } else if (exactNbt && !MaterialMatcher.sameRuntimeFragment(selected, stack)) {
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
            if (MaterialMatcher.sameRuntimeFragment(stack, template)) available += stack.getCount();
        }
        if (available < count) return false;
        int remaining = count;
        for (ItemStack stack : pool) {
            if (remaining <= 0) break;
            if (!MaterialMatcher.sameRuntimeFragment(stack, template)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        return true;
    }

    private static String describeOutputCheck(OutputDeclaration output, long matchedCount) {
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
        ResourceLocation id = BuiltInRegistries.ITEM
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

    private final class EarthHeartTaintWorker implements ConcurrentNodeExecutor.Worker {
        @Override
        public ConcurrentNodeExecutor.Observation observe() {
            if (state == State.ABORTED) return ConcurrentNodeExecutor.Observation.FAILED;
            if (state == State.WAITING_PLAYER_TRANSFORMATION || taintRemaining > 0) {
                return ConcurrentNodeExecutor.Observation.WORKING;
            }
            return ConcurrentNodeExecutor.Observation.SUCCEEDED;
        }
    }

    /**
     * 大地之心污染是同步步骤，但它的配方 id 被建模为 GENERIC；图执行器必须
     * 在拒绝 GENERIC 节点之前先识别它，否则该步骤永远不会真正执行。
     */
    static boolean isEarthHeartTaintStep(CraftingResolver.ResolutionStep step) {
        return step != null
                && CraftingResolver.TAINT_EARTH_HEART_STEP.equals(step.recipeId());
    }

    private CraftingResolver.ResolutionStep graphStep(NodeId nodeId) {
        CraftNode node = graphNodes.get(nodeId);
        if (node == null) throw new IllegalArgumentException("Unknown graph node " + nodeId);
        return ExecutionEquivalence.projectStep(node);
    }

    public boolean isDone() { return state == State.COMPLETED || state == State.ABORTED; }
    public boolean isAborted() { return state == State.ABORTED; }
    public State state() { return state; }
    /**
     * 当前 tick 内是否还能继续推进纯工作台步骤。
     * 真实机器等待、玩家交互等待和最终结算仍保持原有的 tick 边界。
     */
    boolean canAdvanceImmediately(boolean vanillaBudgetAvailable) {
        if (isDone() || useGraphExecution || waitingForMachineLease) return false;
        if (state == State.PENDING) return true;
        if (state != State.EXECUTING) return false;
        if (currentStepIdx >= steps.size()) return true;
        if (vanillaBudgetAvailable) return true;
        return steps.get(currentStepIdx).modType() != ModType.GENERIC;
    }
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
        CraftProgressSnapshot.Reason detailReason = failureCode.progressReason();
        if (detailReason != CraftProgressSnapshot.Reason.NONE) return detailReason;
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
        int maximum = CraftProgressSnapshot.MAX_NODE_DETAILS;
        List<NodeId> order = graph.topologicalOrder();
        if (order.size() <= maximum) {
            List<CraftProgressSnapshot.NodeProgress> result = new ArrayList<>(order.size());
            for (NodeId nodeId : order) result.add(buildGraphNodeProgress(nodeId));
            return List.copyOf(result);
        }

        List<CraftProgressSnapshot.NodeProgress> result = new ArrayList<>(maximum);
        Set<Integer> selected = new HashSet<>(maximum);
        addGraphNodeProgress(result, selected, maximum, nodeId -> {
            DagScheduler.NodeState state = graphScheduler.state(nodeId);
            CraftNodeRuntime runtime = nodeRuntimes.get(nodeId);
            return state == DagScheduler.NodeState.RUNNING
                    || state == DagScheduler.NodeState.FAILED
                    || runtime != null && runtime.isDraining()
                    || !graphFailureDetails.getOrDefault(nodeId, "").isEmpty();
        });
        addGraphNodeProgress(result, selected, maximum,
                nodeId -> graphScheduler.state(nodeId) == DagScheduler.NodeState.READY);
        addGraphNodeProgress(result, selected, maximum,
                nodeId -> graphScheduler.state(nodeId) == DagScheduler.NodeState.BLOCKED);
        addGraphNodeProgress(result, selected, maximum, nodeId -> true);
        return List.copyOf(result);
    }

    private void addGraphNodeProgress(List<CraftProgressSnapshot.NodeProgress> result,
                                      Set<Integer> selected,
                                      int maximum,
                                      Predicate<NodeId> predicate) {
        if (result.size() >= maximum) return;
        for (NodeId nodeId : graph.topologicalOrder()) {
            if (result.size() >= maximum) return;
            if (!predicate.test(nodeId) || !selected.add(nodeId.value())) continue;
            result.add(buildGraphNodeProgress(nodeId));
        }
    }

    private CraftProgressSnapshot.NodeProgress buildGraphNodeProgress(NodeId nodeId) {
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
        CraftFailureCode nodeFailureCode = runtime != null
                ? runtime.failureCode()
                : graphFailureCodes.getOrDefault(nodeId, CraftFailureCode.NONE);
        return new CraftProgressSnapshot.NodeProgress(nodeId.value(),
                progressNodeState(schedulerState),
                node != null ? node.recipeId().toString() : "",
                node != null ? node.modTypeId() : "",
                displayOutput(node),
                completedOps, totalOps, runningOps,
                runtime != null ? runtime.machineLabel() : "",
                nodeFailureCode.progressReason(), detail,
                runtime != null && runtime.isDraining());
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
            if (i == currentStepIdx && stepRemaining > 0) {
                completedOps = completedFlatOperations(totalOps, stepRemaining);
            }
            int runningOps = i == currentStepIdx && currentDelegate != null
                    ? Math.min(totalOps, currentDelegate instanceof ParallelCraftGroup group
                            ? group.getRunningOperations() : 1) : 0;
            if (i == currentStepIdx && currentDelegate instanceof ParallelCraftGroup group) {
                completedOps = completedFlatWindowOperations(
                        totalOps, stepRemaining, group.getCompletedOperations());
            }
            String detail = i == currentStepIdx ? abortReason : "";
            CraftProgressSnapshot.Reason nodeReason = i == currentStepIdx
                    ? failureCode.progressReason() : CraftProgressSnapshot.Reason.NONE;
            result.add(new CraftProgressSnapshot.NodeProgress(i, nodeState,
                    step.recipeId().toString(), step.modType().id(), displayOutput(step),
                    completedOps, totalOps, runningOps, i == currentStepIdx ? flatMachineLabel() : "",
                    nodeReason, detail,
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
                .map(output -> output.material().toStack(SaturatingLongMath.clampToInt(
                        Math.max(1L, output.quantity() / Math.max(1, node.executions())))))
                .orElseGet(() -> node.syntheticOutput() == null
                        ? ItemStack.EMPTY : node.syntheticOutput().copy());
    }

    private ItemStack displayOutput(CraftingResolver.ResolutionStep step) {
        if (step.productionTarget() != null) {
            // 展示材料身份即可，避免把整单需求量当作普通物品堆叠数量发送。
            return step.productionTarget().displayStack();
        }
        if (step.syntheticOutput() != null && !step.syntheticOutput().isEmpty()) {
            return step.syntheticOutput().copy();
        }
        Recipe<?> recipe = recipeForStep(step);
        return recipe == null || server.overworld() == null ? ItemStack.EMPTY : ModRecipeHandlers.tryGetResultItem(
                recipe, server.overworld().registryAccess());
    }

    private String flatMachineLabel() {
        if (currentDelegate instanceof ParallelCraftGroup group) return group.machineLabel();
        MachineLeaseRegistry.Lease lease = flatOperationSession == null
                ? null : flatOperationSession.machineLease();
        if (lease == null) return machineStartFailureLabel;
        MachineLeaseRegistry.MachineKey machine = lease.machine();
        return machine.dimension() + "@" + machine.position().toShortString();
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

    /** Final outputs fully accepted by the player inventory or selected storage. */
    public List<ItemStack> deliveredFinalOutputs() {
        return StackCopies.excludingEmpty(deliveredFinalOutputs);
    }

    /** Final outputs retained in recovery or dropped because no safe sink accepted them. */
    public List<ItemStack> recoveryFinalOutputs() {
        return StackCopies.excludingEmpty(recoveryFinalOutputs);
    }

    public boolean belongsTo(UUID playerId) {
        return this.playerId.equals(playerId);
    }

    private boolean startEarthHeartTaint(ServerPlayer player, int count) {
        return startEarthHeartTaint(player, count, null);
    }

    private boolean startEarthHeartTaint(ServerPlayer player, int count, @Nullable NodeId nodeId) {
        // 旧语义要求手动把大地之心放进玩家背包再污染；没有诅咒之戒也应
        // 保留这条路径，只提示玩家，而不是直接中止整个合成。
        if (!hasEquippedCursedRing(player)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.async.earth_heart.warn.cursed_ring_missing"));
        }
        int slot = player.getInventory().getFreeSlot();
        if (slot < 0 || slot >= player.getInventory().items.size()) {
            abort("Earth Heart tainting requires an empty main-inventory slot", Component.translatable("rsi.async.earth_heart.error.empty_slot_required"));
            return false;
        }
        ItemStack heart = ItemStack.EMPTY;
        while (!taintHeartQueue.isEmpty()) {
            ItemStack queued = taintHeartQueue.remove(0);
            if (isPlainEarthHeart(queued)) {
                heart = queued.copyWithCount(1);
                break;
            }
        }
        if (heart.isEmpty()) {
            for (ItemStack vi : virtualInventory) {
                if (isPlainEarthHeart(vi)) {
                    heart = vi.split(1);
                    break;
                }
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
        this.earthHeartTaintNodeId = nodeId;
        this.state = State.WAITING_PLAYER_TRANSFORMATION;
        RSIntegrationMod.LOGGER.info(ctx.format(
                "Earth Heart taint started: slot={} count={}"), slot, taintRemaining);
        // Heart is now physically in the player's slot, not virtualInventory -
        // snapshot so an abort mid-taint doesn't re-mint it into the network
        // while it also sits in the player's inventory.
        snapshotCommittedVirtual();
        player.sendSystemMessage(Component.translatable("rsi.async.earth_heart.info.waiting"));
        return true;
    }

    /**
     * 图执行器不会为纯 worker 节点做增量产物发布；污染完成的大地之心必须
     * 主动写回该节点的 ProducerOutput，否则下游节点永远等不到材料。
     */
    private void publishTaintedEarthHeart(NodeId nodeId, ItemStack tainted, ServerPlayer player) {
        CraftNode node = graphNodes.get(nodeId);
        if (graphMaterials == null || node == null || node.outputs().isEmpty()) {
            addToVirtualInventory(tainted);
            return;
        }
        OutputDeclaration output = node.outputs().get(0);
        ItemStack published = MaterialMatcher.alignToDeclaration(output.material(), tainted);
        try {
            graphMaterials.publishActual(
                    new MaterialSource.ProducerOutput(output.id()), output.material(), published);
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.warn(ctx.format(
                    "Failed to publish tainted Earth Heart for node={}; falling back to virtual inventory"),
                    nodeId, exception);
            addToVirtualInventory(tainted);
        }
        MaterialSources.invalidateFor(player);
    }

    private static boolean isPlainEarthHeart(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!new ResourceLocation("enigmaticlegacy", "earth_heart")
                .equals(ForgeRegistries.ITEMS.getKey(stack.getItem()))) return false;
        return stack.getTag() == null || !stack.getTag().getBoolean("isTainted");
    }

    private boolean tickEarthHeartTaint(ServerPlayer player) {
        if (++taintWaitTicks > 1200) {
            abortWithoutRefund("Earth Heart tainting timed out; the heart remains in your inventory",
                    Component.translatable("rsi.async.earth_heart.error.timeout"),
                    CraftFailureCode.TIMEOUT);
            return true;
        }
        if (taintSlot < 0 || taintSlot >= player.getInventory().items.size()) {
            abortWithoutRefund("Earth Heart taint slot became invalid",
                    Component.translatable("rsi.async.earth_heart.error.slot_invalid"),
                    CraftFailureCode.RESOURCE_CONFLICT);
            return true;
        }
        ItemStack stack = player.getInventory().items.get(taintSlot);
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (!new ResourceLocation("enigmaticlegacy", "earth_heart").equals(id)) {
            abortWithoutRefund("Earth Heart was moved; it remains with the player",
                    Component.translatable("rsi.async.earth_heart.error.moved"),
                    CraftFailureCode.RESOURCE_CONFLICT);
            return true;
        }
        if (stack.getTag() == null || !stack.getTag().getBoolean("isTainted")) return false;

        ItemStack recovered = stack.split(1);
        if (stack.isEmpty()) player.getInventory().items.set(taintSlot, ItemStack.EMPTY);
        player.getInventory().setChanged();
        if (earthHeartTaintNodeId != null) {
            publishTaintedEarthHeart(earthHeartTaintNodeId, recovered, player);
        } else {
            addToVirtualInventory(recovered);
        }
        taintRemaining--;
        RSIntegrationMod.LOGGER.info(ctx.format(
                "Earth Heart tainted: remaining={} slot={} node={}"),
                taintRemaining, taintSlot, earthHeartTaintNodeId);
        taintSlot = -1;
        if (taintRemaining > 0) {
            state = State.EXECUTING;
            return !startEarthHeartTaint(player, taintRemaining, earthHeartTaintNodeId);
        }
        earthHeartTaintNodeId = null;
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
            for (ItemStack stack : CuriosAccess
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
        ServerLevel level = server.overworld();
        boolean pureChain = steps.stream().allMatch(step ->
                step.modType() == ModType.GENERIC
                        && !step.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP));
        VanillaBatchSlice slice = BatchDispatchPlanner.planVanillaBatchSlice(
                steps, startIdx, stepRemaining, operationBudget,
                step -> pureChain && level != null && StableRecipeBatch.isEligible(
                        level, step, targetOutput, supplementalMaterialLocks));
        if (!slice.steps().isEmpty()) {
            FlatCraftExecutor.ExecutionResult result =
                    executeVanillaStepsInlineDetailed(slice.steps(), online,
                            virtualInventory, ledger, true);
            if (result.status() == FlatCraftExecutor.Status.DEFERRED) {
                vanillaBatchDeferredTicks++;
                waitingForVanillaBudget = true;
                int retryLimit = configuredStableBatchRetryTicks();
                RSIntegrationMod.LOGGER.debug(ctx.format(
                        "Vanilla batch deferred ({}/{}): {}"),
                        vanillaBatchDeferredTicks, retryLimit,
                        result.detail());
                if (vanillaBatchDeferredTicks >= retryLimit) {
                    abort("Vanilla batch deferred without progress: "
                                    + result.detail(),
                            Component.translatable(
                                    "rsi.async.abort.stable_batch_deferred",
                                    retryLimit / 20));
                }
                return startIdx;
            }
            vanillaBatchDeferredTicks = 0;
            if (result.status() == FlatCraftExecutor.Status.FAILURE) {
                if (state != State.ABORTED) {
                    abort("Vanilla batch execution failed: " + result.detail(),
                            Component.translatable("rsi.generic.error.auto_craft_failed"));
                }
                return startIdx;
            }
        }
        stepRemaining = slice.remainingExecutions();
        return slice.nextStepIndex();
    }

    static int flatDispatchWindow(int remainingOperations, int dispatchLimit) {
        return BatchDispatchPlanner.flatDispatchWindow(remainingOperations, dispatchLimit);
    }

    static int remainingAfterFlatBatch(int remainingOperations, int completedOperations) {
        return BatchDispatchPlanner.remainingAfterFlatBatch(remainingOperations, completedOperations);
    }

    static int completedFlatWindowOperations(int totalOperations, int remainingOperations,
                                              int completedInWindow) {
        return BatchDispatchPlanner.completedFlatWindowOperations(
                totalOperations, remainingOperations, completedInWindow);
    }

    static VanillaBatchSlice planVanillaBatchSlice(
            List<CraftingResolver.ResolutionStep> sourceSteps,
            int startIdx, int currentRemaining, int operationBudget) {
        return BatchDispatchPlanner.planVanillaBatchSlice(
                sourceSteps, startIdx, currentRemaining, operationBudget);
    }

    record VanillaBatchSlice(
            List<CraftingResolver.ResolutionStep> steps,
            int nextStepIndex,
            int remainingExecutions) {}

    boolean isWaitingForVanillaBudget() {
        return waitingForVanillaBudget;
    }

    private static int configuredOperationsPerDispatch() {
        return BatchDispatchPlanner.configuredOperationsPerDispatch();
    }

    private static int configuredStableBatchRetryTicks() {
        try {
            return Math.max(1,
                    RSIntegrationConfig.CRAFTING_STABLE_BATCH_RETRY_SECONDS.get()) * 20;
        } catch (Exception ignored) {
            return RSIntegrationConfig.DEFAULT_CRAFTING_STABLE_BATCH_RETRY_SECONDS * 20;
        }
    }

    /**
     * Execute vanilla crafting steps inline, using the chain's virtual inventory
     * and ledger so intermediate outputs feed forward across the entire chain.
     */
    private boolean executeVanillaStepsInline(List<CraftingResolver.ResolutionStep> vanillaSteps,
                                              ServerPlayer online) {
        return executeVanillaStepsInlineExtracted(vanillaSteps, online,
                virtualInventory, ledger, true);
    }

    private boolean executeVanillaStepsInline(List<CraftingResolver.ResolutionStep> vanillaSteps,
                                              ServerPlayer online,
                                              List<ItemStack> workingInventory) {
        return executeVanillaStepsInlineExtracted(vanillaSteps, online,
                workingInventory, ledger, true);
    }

    private boolean executeVanillaStepsInline(List<CraftingResolver.ResolutionStep> vanillaSteps,
                                              ServerPlayer online,
                                              List<ItemStack> workingInventory,
                                              ExtractionLedger executionLedger,
                                              boolean allowPhysicalFallback) {
        return executeVanillaStepsInlineExtracted(vanillaSteps, online,
                workingInventory, executionLedger, allowPhysicalFallback);
    }

    private boolean executeVanillaStepsInlineExtracted(
            List<CraftingResolver.ResolutionStep> vanillaSteps,
            ServerPlayer online, List<ItemStack> workingInventory,
            ExtractionLedger executionLedger, boolean allowPhysicalFallback) {
        return executeVanillaStepsInlineDetailed(
                vanillaSteps, online, workingInventory, executionLedger,
                allowPhysicalFallback).status() == FlatCraftExecutor.Status.SUCCESS;
    }

    private FlatCraftExecutor.ExecutionResult executeVanillaStepsInlineDetailed(
            List<CraftingResolver.ResolutionStep> vanillaSteps,
            ServerPlayer online, List<ItemStack> workingInventory,
            ExtractionLedger executionLedger, boolean allowPhysicalFallback) {
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return FlatCraftExecutor.ExecutionResult.failure("overworld unavailable");
        }
        return FlatCraftExecutor.executeDetailed(vanillaSteps, online, workingInventory,
                executionLedger, allowPhysicalFallback,
                new FlatCraftExecutor.Context(overworld, ctx, steps, targetOutput,
                        new FlatCraftExecutor.Host() {
                            @Override
                            public ItemStack reserve(ExtractionLedger ledger,
                                                      Ingredient ingredient, int amount,
                                                      ServerPlayer player) {
                                return reserveIngredient(ledger, ingredient, amount, player);
                            }

                            @Override
                            public void onMissing(Ingredient ingredient, ResourceLocation stepId,
                                                   ExtractionLedger ledger,
                                                   boolean allowPhysicalFallback) {
                                logMissingIngredient(ingredient, stepId);
                                logVirtualInventory("at failure for step " + stepId);
                                logLedgerState(ledger);
                                if (allowPhysicalFallback) {
                                    abort("Missing: " + describeIngredientSafe(ingredient),
                                            Component.translatable("rsi.async.abort.missing_material",
                                                    nameIngredientSafe(ingredient)));
                                }
                            }

                            @Override
                            public void logVirtualInventory(String context) {
                                AsyncCraftChain.this.logVirtualInventory(context);
                            }

                            @Override
                            public void logLedgerState(ExtractionLedger ledger) {
                                AsyncCraftChain.this.logLedgerState(ledger);
                            }
                        }, supplementalMaterialLocks));
    }

    //  multi-block step execution

    private IBatchDelegate startModStep(CraftingResolver.ResolutionStep step, ServerPlayer online) {
        if (!waitingForMachineLease) {
            machineWaitMessage = null;
            machineWaitDetail = null;
        }
        machineStartFailureMessage = null;
        machineStartFailureDetail = null;
        machineStartFailureLabel = "";
        materialReservationFailureDetail = null;
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
            List<BoundMachine> blockedMatches =
                    AltarBindingRegistry.getBlockedMachinesForRecipe(
                            online, step.modType(), step.recipeId());
            if (!blockedMatches.isEmpty()) {
                RSIntegrationMod.LOGGER.debug(ctx.format(
                        "All matching machines are excluded by the automation blocklist: "
                                + "modType={} recipe={} blocked={}"),
                        step.modType(), step.recipeId(), blockedMatches.size());
                online.sendSystemMessage(Component.translatable(
                        "rsi.async.error.machine_automation_blocked", step.recipeId()));
                return null;
            }
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
        machines = MachineDispatchPlanner.deduplicate(machines);
        int boundMachineCount = machines.size();
        flatWaitingMachines = leasedMachineKeys(machines, step.modType().id());
        machines = MachineDispatchPlanner.filterUnleased(
                machines, machineLeases, step.modType().id());
        if (MachineDispatchPlanner.classifyLeaseAvailability(
                boundMachineCount, machines.size())
                == MachineDispatchPlanner.LeaseAvailability.ALL_LEASED) {
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
            if (aSame != bSame) return aSame ? -1 : 1;
            return IronFurnaceMachinePriority.compare(a, b);
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
        } else if (flatDispatchWindow(stepRemaining, configuredOperationsPerDispatch()) <= 1) {
            RSIntegrationMod.LOGGER.debug(ctx.format("[LB] skipped: dispatchWindow<=1 remaining={}"), stepRemaining);
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

        MachineDispatchPlanner.CandidateSelection candidateSelection =
                MachineDispatchPlanner.filterCandidates(
                machines,
                machine -> {
                    ResourceKey<Level> dimKey = ResourceKey.create(
                            Registries.DIMENSION, machine.dim());
                    ServerLevel machineLevel = server.getLevel(dimKey);
                    return machineLevel != null && machineLevel.hasChunkAt(machine.pos());
                },
                machine -> {
                    ResourceKey<Level> dimKey = ResourceKey.create(
                            Registries.DIMENSION, machine.dim());
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
        String fatalMachineLabel = "";
        Component fatalUserMessage = null;
        for (BoundMachine m : candidateSelection.usable()) {
            IBatchDelegate candidate = null;
            boolean retained = false;
            try {
                candidate = createStepDelegate(step, m.type());
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
                    if (machineWaitDetail == null) {
                        machineWaitDetail = preparation.detail();
                        machineWaitMessage = preparation.userMessage();
                    }
                } else if (fatalDetail.isEmpty()) {
                    fatalDetail = preparation.detail();
                    fatalMachineLabel = m.dim() + "@" + m.pos().toShortString();
                    fatalUserMessage = preparation.userMessage();
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug(ctx.format("prepare failed for machine at {}"), m.pos(), e);
                if (fatalDetail.isEmpty()) {
                    fatalDetail = e.getMessage();
                    fatalMachineLabel = m.dim() + "@" + m.pos().toShortString();
                }
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
            machineStartFailureDetail = fatalDetail.isEmpty() ? null : fatalDetail;
            machineStartFailureLabel = fatalMachineLabel;
            machineStartFailureMessage = fatalUserMessage != null
                    ? fatalUserMessage
                    : protectionRejection
                    ? Component.translatable("rsi.error.protection_denied")
                    : unloadedRejection
                    ? Component.translatable("rsi.error.chunk_unloaded")
                    : Component.translatable("rsi.async.error.machine_valid_failed", step.recipeId());
            return null;
        }

        if (matchedMachine.type() != step.modType()) {
            RSIntegrationMod.LOGGER.info(ctx.format(
                    "[RSI-Craft] compatible binding route recipe={} recipeType={} machineType={} delegate={} machine={}"),
                    step.recipeId(), step.modType().id(), matchedMachine.type().id(),
                    delegate.getClass().getSimpleName(), matchedMachine.pos());
        }

        final IBatchDelegate startedDelegate = delegate;
        attachMagicReservation(startedDelegate);
        try {
            Component outputStorageFailure = startedDelegate.validateOutputStorage(online);
            if (outputStorageFailure != null) {
                machineStartFailureDetail = "output storage preflight rejected";
                machineStartFailureMessage = outputStorageFailure;
                releasePreparationQuietly(startedDelegate);
                return null;
            }
            int configuredLimit = configuredOperationsPerDispatch();
            int delegateLimit = startedDelegate.flatBatchOperationLimit(configuredLimit);
            int allowedBatch = Math.min(stepRemaining, Math.max(1, delegateLimit));
            int flatBatch = startedDelegate.prepareFlatBatch(allowedBatch);
            if (flatBatch <= 0 || flatBatch > allowedBatch) {
                RSIntegrationMod.LOGGER.warn(ctx.format(
                        "Delegate selected invalid flat batch size {} for {} permitted operation(s)"),
                        flatBatch, allowedBatch);
                releasePreparationQuietly(startedDelegate);
                return null;
            }
            machineCount = flatBatch;
            List<IngredientSpec> flatBatchSpecs =
                    startedDelegate.getFlatBatchRequiredMaterials(flatBatch);
            List<IngredientSpec> specs = flatBatchSpecs != null
                    ? flatBatchSpecs : startedDelegate.getRequiredMaterials();
            if (specs != null && !specs.isEmpty()) {
                if (startedDelegate instanceof AbstractBatchDelegate abstractDelegate) {
                    abstractDelegate.setStorageEndpoint(storageEndpoint);
                }
                startedDelegate.configureMaterialReservation(ledger, online);
                FlatMaterialBatch reservation = reserveFlatMaterialBatch(flatBatch,
                        startedDelegate::prepareFlatBatch,
                        executions -> {
                            List<IngredientSpec> exactBatchSpecs =
                                    startedDelegate.getFlatBatchRequiredMaterials(executions);
                            List<IngredientSpec> reservationSpecs = exactBatchSpecs != null
                                    ? exactBatchSpecs
                                    : scaleGraphSpecsForExecutions(
                                            startedDelegate.getRequiredMaterials(),
                                            executions);
                            return preReserveStepMaterials(reservationSpecs, online, null,
                                    executions == 1);
                        });
                List<ItemStack> materials = reservation.materials();
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
                machineCount = reservation.executions();
                if (machineCount != flatBatch) {
                    RSIntegrationMod.LOGGER.info(ctx.format(
                            "Flat material batch reduced: recipe={} remainingOperations={} requestedBatch={} admittedBatch={} reason=material_reservation; later batches may reuse settled outputs"),
                            step.recipeId(), stepRemaining, flatBatch, machineCount);
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
                boolean bufferedStart = startedDelegate.supportsInputBuffer();
                InputBufferPlan inputBuffer = bufferedStart
                        ? startedDelegate.inputBufferPlan(machineCount)
                        : InputBufferPlan.none();
                OperationStartContext startContext = OperationStartContext.chainReserved(
                        online, ledger, materialPlanFor(startedDelegate), materials, inputBuffer);
                if (bufferedStart && !startContext.buffered()) {
                    RSIntegrationMod.LOGGER.error(ctx.format(
                            "Input-buffer plan could not bind reserved materials: recipe={} delegate={} operations={} reservedStacks={}"),
                            step.recipeId(), startedDelegate.getClass().getSimpleName(), machineCount,
                            materials.size());
                }
                if (!flatOperationSession.tryStart(
                        () -> startedDelegate.startOperation(startContext))) {
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
                    // 必须在 onBatchFailed 清理委托状态前取出失败详情，否则报告只剩兜底文案
                    String startDetail = startedDelegate.lastStartFailureDetail();
                    if (startDetail != null && !startDetail.isBlank()) {
                        machineStartFailureDetail = startDetail;
                    }
                    RSIntegrationMod.LOGGER.warn(ctx.format(
                            "Delegate startPreparedOperation failed for {} detail={}"),
                            step.recipeId(), startDetail == null ? "<none>" : startDetail);
                    Component failureTarget = stepFailureTarget(step, online);
                    machineStartFailureMessage = Component.translatable(
                            "rsi.async.abort.machine_start_failed", failureTarget);
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.craft_failed", failureTarget));
                    try { delegate.onBatchFailed(online, "startPreparedOperation failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during startPreparedOperation cleanup"), fe);
}
                    // abort() refunds the ledger, so we must NOT refund here
                    return null;
                }
                // Some machines (notably CrockPot's birdcage) determine their
                // output only while starting. Arm a second capture after that
                // point so its delayed world drop cannot be picked up first.
                armOutputCapture(startedDelegate, online);
            } else {
                // Private-ledger path: startSingleOperation's ensureMaterialAvailable
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
                        () -> startedDelegate.startOperation(
                                OperationStartContext.delegateExtracted(online, ledger)))) {
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
                    RSIntegrationMod.LOGGER.warn(ctx.format("Delegate startSingleOperation failed for {}"),
                            step.recipeId());
                    try { delegate.onBatchFailed(online, "startSingleOperation failed"); } catch (Exception fe) {
    RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during startSingleOperation cleanup"), fe);
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
            int configuredLimit = configuredOperationsPerDispatch();
            int allowedBatch = genericBatchLimit(delegate, stepRemaining, configuredLimit);
            int preparedBatch = delegate.prepareFlatBatch(allowedBatch);
            if (preparedBatch <= 0 || preparedBatch > allowedBatch) {
                RSIntegrationMod.LOGGER.warn(ctx.format(
                        "Generic delegate selected invalid flat batch size {} for {} permitted operation(s)"),
                        preparedBatch, allowedBatch);
                return null;
            }
            machineCount = preparedBatch;
            List<IngredientSpec> exactBatchSpecs =
                    delegate.getFlatBatchRequiredMaterials(preparedBatch);
            List<IngredientSpec> specs = exactBatchSpecs != null
                    ? exactBatchSpecs
                    : scaleGraphSpecsForExecutions(
                            delegate.getRequiredMaterials(),
                            preparedBatch);
            OperationStartContext startContext;
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
                startContext = OperationStartContext.chainReserved(
                        online, ledger, materialPlanFor(delegate), materials, InputBufferPlan.none());
            } else {
                startContext = OperationStartContext.delegateExtracted(online, ledger);
            }
            if (!delegate.startOperation(startContext)) {
                RSIntegrationMod.LOGGER.warn(ctx.format(
                        "Unified operation start failed for generic step {}"), step.recipeId());
                Component failureTarget = stepFailureTarget(step, online);
                machineStartFailureMessage = Component.translatable(
                        "rsi.async.abort.machine_start_failed", failureTarget);
                online.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.craft_failed", failureTarget));
                try { delegate.onBatchFailed(online, "unified operation start failed"); }
                catch (Exception fe) {
                    RSIntegrationMod.LOGGER.error(ctx.format(
                            "onBatchFailed threw during unified start cleanup"), fe);
                }
                return null;
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

    static int genericBatchLimit(IBatchDelegate delegate, int remainingOperations,
                                 int configuredLimit) {
        int delegateLimit = Math.max(1,
                delegate.flatBatchOperationLimit(Math.max(1, configuredLimit)));
        return Math.min(Math.max(1, remainingOperations), delegateLimit);
    }

    /** 失败提示优先显示产物本地化名称，无法解析时才回退到配方 ID。 */
    static Component stepFailureTarget(CraftingResolver.ResolutionStep step,
                                       ServerPlayer player) {
        ItemStack output = step.syntheticOutput();
        if ((output == null || output.isEmpty()) && step.productionTarget() != null) {
            output = step.productionTarget().displayStack();
        }
        if (output == null || output.isEmpty()) {
            Recipe<?> recipe = player.serverLevel().getRecipeManager()
                    .byKey(step.recipeId()).orElse(null);
            if (recipe != null) {
                output = ModRecipeHandlers.tryGetResultItem(
                        recipe, player.serverLevel().registryAccess());
            }
        }
        return output != null && !output.isEmpty()
                ? output.getHoverName()
                : Component.literal(step.recipeId().toString());
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
        int dispatchLimit = flatDispatchOperationLimit(machines, step, online);
        int window = flatDispatchWindow(stepRemaining, dispatchLimit);
        while (window > 1) {
            materialReservationFailureDetail = null;
            IBatchDelegate group = tryStartParallelWindow(machines, step, online, window);
            if (group != null || ledger.isCommitted() || materialReservationFailureDetail == null) {
                return group;
            }
            restoreVirtualFromCommitted();
            if (ledger.state() != ExtractionLedger.State.IDLE) ledger.reset();
            window /= 2;
        }
        return null;
    }

    private int flatDispatchOperationLimit(List<BoundMachine> machines,
                                           CraftingResolver.ResolutionStep step,
                                           ServerPlayer online) {
        int configuredLimit = configuredOperationsPerDispatch();
        if (stepRemaining <= configuredLimit) return configuredLimit;
        List<Integer> physicalCapacities = new ArrayList<>();
        for (BoundMachine machine : machines) {
            IBatchDelegate candidate = null;
            try {
                candidate = createStepDelegate(step, machine.type());
                if (candidate == null) continue;
                if (candidate instanceof AbstractBatchDelegate abstractDelegate) {
                    abstractDelegate.setStorageEndpoint(storageEndpoint);
                }
                IBatchDelegate.PreparationResult preparation = PreparationMessageScope.prepare(
                        candidate, online, step.recipeId(), machine.dim(), machine.pos());
                if (preparation.state() == IBatchDelegate.PreparationState.READY
                        && candidate.expandsFlatBatchOperationLimit()) {
                    // Ask for the native capacity without injecting the generic
                    // per-dispatch baseline into every worker. The group limit is
                    // the sum of independently usable physical buffers.
                    physicalCapacities.add(candidate.flatBatchOperationLimit(1));
                }
            } catch (RuntimeException exception) {
                RSIntegrationMod.LOGGER.debug(ctx.format(
                        "Flat dispatch capacity probe failed at {}"), machine.pos(), exception);
            } finally {
                if (candidate != null) releasePreparationQuietly(candidate);
            }
        }
        return MachineDispatchPlanner.combinedParallelDispatchLimit(
                configuredLimit, physicalCapacities);
    }

    private IBatchDelegate tryStartParallelWindow(List<BoundMachine> machines,
                                                  CraftingResolver.ResolutionStep step,
                                                  ServerPlayer online, int operationCount) {
        if (server == null) return null;

        IBatchDelegate capabilityProbe = createStepDelegate(step, machines.get(0).type());
        List<BoundMachine> available;
        GraphConcurrencyPolicy.Decision capabilityDecision;
        try {
            available = LoadBalancer.filterAvailable(machines, server, capabilityProbe);
            capabilityDecision = concurrencyDecision(step, capabilityProbe);
        } finally {
            if (capabilityProbe != null) releasePreparationQuietly(capabilityProbe);
        }
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
                        Registries.DIMENSION, machine.dim());
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
        int cap = Math.min(operationCount, Math.min(craftOperationBudget.availableCapacity(),
                globalOperationBudget.availableCapacity()));
        if (cap < 2) return null;

        // Build a parallel group -constructor internally creates and validates
        // one delegate per machine. Pass the same recipe-aware capability contract
        // used by graph execution; otherwise the operation kernel accepts the group
        // but rejects every child as capability-exclusive after materials are committed.
        if (capabilityDecision.exclusive()) {
            RSIntegrationMod.LOGGER.debug(ctx.format(
                    "[LB] parallel disabled by capability policy: {}"), capabilityDecision.reason());
            return null;
        }
        ParallelCraftGroup group = new ParallelCraftGroup(available, step.modType(),
                step.recipeId(), online, operationCount, step.inferMode(),
                capabilityDecision.capabilities(), storageEndpoint, cap);
        group.setDispatchAllowed(() -> dispatchAllowed.getAsBoolean());
        group.setMachineLeases(machineLeases);
        if (!validatePreparedDelegate(
                group, online, step.recipeId(), null, BlockPos.ZERO)) {
            RSIntegrationMod.LOGGER.debug(ctx.format("Parallel group empty -all children failed validateAndInit"));
            releasePreparationQuietly(group);
            return null;
        }
        if (group.getChildCount() < 2) {
            releasePreparationQuietly(group);
            return null;
        }

        this.machineCount = operationCount;
        group.setMachineServer(server);
        if (step.productionTarget() != null) {
            ItemStack display = step.productionTarget().displayStack();
            IntegrationResourceKey resource =
                    step.productionTarget().resource();
            if (resource == null && !display.isEmpty()) {
                resource = IntegrationResourceKey.item(display);
            }
            if (!display.isEmpty()
                    && resource != null
                    && resource.kind()
                    == IntegrationResourceKey.ResourceKind.ITEM) {
                group.setTargetOutput(display);
            }
            group.setTargetResource(resource);
        } else if (step.syntheticOutput() != null
                && !step.syntheticOutput().isEmpty()) {
            group.setTargetOutput(step.syntheticOutput());
            group.setTargetResource(
                    IntegrationResourceKey.item(step.syntheticOutput()));
        } else if (targetOutput != null && isPrimaryStep(currentStepIdx)) {
            group.setTargetOutput(targetOutput);
            group.setTargetResource(targetResource);
        } else if (isPrimaryStep(currentStepIdx)
                && targetResource != null) {
            group.setTargetResource(targetResource);
        }
        IBatchDelegate started = startParallelStep(group, step, online);
        if (started != null) {
            RSIntegrationMod.LOGGER.info(ctx.format(
                    "Load-balanced window: recipe={} machines={} operations={} remainingBefore={} dispatchLimit={}"),
                    step.recipeId(), group.getChildCount(), operationCount, stepRemaining,
                    configuredOperationsPerDispatch());
        }
        return started;
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
                            operationSpecs, parallel.getChildCount(),
                            parallel.getTotalOperations(), online);
                    if (reserved == null) {
                        materials = null;
                    } else {
                        materials = new ArrayList<>();
                        List<ExtractionLedger.ReservationToken> tokens = new ArrayList<>();
                        List<List<ItemStack>> virtualDebits = new ArrayList<>();
                        for (ReservedOperation operation : reserved.operations()) {
                            materials.addAll(StackCopies.includingEmpty(operation.materials()));
                            tokens.add(operation.token());
                            virtualDebits.add(StackCopies.excludingEmpty(operation.virtualDebits()));
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
                    RSIntegrationMod.LOGGER.debug(ctx.format("Reducing or falling back after parallel reservation failure for {}"),
                            step.recipeId());
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
                OperationStartContext startContext;
                if (group instanceof ParallelCraftGroup parallel) {
                    List<IngredientSpec> perOperation = parallel.getFlatOperationMaterials();
                    if (perOperation == null || perOperation.isEmpty()) {
                        throw new IllegalStateException(
                                "parallel group lost its per-operation material layout");
                    }
                    startContext = OperationStartContext.repeatedChainReserved(
                            online, ledger, parallel.operationMaterialPlan(),
                            parallel.getTotalOperations(), perOperation.size(), materials);
                } else {
                    startContext = OperationStartContext.chainReserved(
                            online, ledger, materialPlanFor(group), materials,
                            InputBufferPlan.none());
                }
                if (!group.startOperation(startContext)) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("Parallel group unified start failed for {}"),
                            step.recipeId());
                    Component failureTarget = stepFailureTarget(step, online);
                    machineStartFailureMessage = Component.translatable(
                            "rsi.async.abort.machine_start_failed", failureTarget);
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.craft_failed", failureTarget));
                    try { group.onBatchFailed(online, "unified start failed"); } catch (Exception fe) {
                        RSIntegrationMod.LOGGER.error(ctx.format("onBatchFailed threw during parallel cleanup"), fe);
                    }
                    // abort() refunds the ledger, so we must NOT refund here
                    return null;
                }
            } else {
                // Fallback: each child self-extracts (no virtualInventory visibility)
                if (!group.startOperation(
                        OperationStartContext.delegateExtracted(online, ledger))) {
                    RSIntegrationMod.LOGGER.warn(ctx.format("Parallel group startSingleOperation failed for {}"),
                            step.recipeId());
                    Component failureTarget = stepFailureTarget(step, online);
                    machineStartFailureMessage = Component.translatable(
                            "rsi.async.abort.machine_start_failed", failureTarget);
                    online.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.craft_failed", failureTarget));
                    try { group.onBatchFailed(online, "startSingleOperation failed"); } catch (Exception fe) {
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
            int workerCount, int operationCount, ServerPlayer online) {
        List<ItemStack> virtualSnapshot = StackCopies.excludingEmpty(virtualInventory);
        List<ReservedOperation> reservations = new ArrayList<>();
        int effectiveWorkers = Math.min(Math.max(1, workerCount), operationCount);
        List<ItemStack> reusable = new ArrayList<>();
        List<List<Integer>> reusableEntryIds = new ArrayList<>();
        for (int worker = 0; worker < effectiveWorkers; worker++) {
            reusableEntryIds.add(new ArrayList<>());
        }
        List<Integer> reusableIndices = reusableMaterialIndices(specs, specs.size());
        for (int i : reusableIndices) {
            IngredientSpec spec = specs.get(i);
            for (int worker = 0; worker < effectiveWorkers; worker++) {
                List<IngredientSpec> one = List.of(new IngredientSpec(
                        spec.ingredient(), spec.count(), spec.consumption()));
                int reusableMark = ledger.reservationMark();
                List<ItemStack> material = preReserveStepMaterials(one, online, null, false);
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
            List<ItemStack> materials = preReserveStepMaterials(perOperation, online, virtualDebits, false);
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
        return preReserveParallelOperations(specs, 1, operationCount, online);
    }

    private void restoreVirtualSnapshot(List<ItemStack> snapshot) {
        virtualInventory.clear();
        virtualInventory.addAll(StackCopies.excludingEmpty(snapshot));
    }

    record FlatMaterialBatch(int executions, List<ItemStack> materials) {}

    static FlatMaterialBatch reserveFlatMaterialBatch(
            int preparedBatch, IntUnaryOperator prepare,
            IntFunction<List<ItemStack>> reserve) {
        MaterialReservationPlanner.FlatMaterialBatch result =
                MaterialReservationPlanner.reserveFlatMaterialBatch(preparedBatch, prepare, reserve);
        return new FlatMaterialBatch(result.executions(), result.materials());
    }

    private List<ItemStack> preReserveStepMaterials(List<IngredientSpec> specs, ServerPlayer online) {
        return preReserveStepMaterials(specs, online, null);
    }

    private List<ItemStack> preReserveStepMaterials(List<IngredientSpec> specs, ServerPlayer online,
                                                    @Nullable List<ItemStack> virtualDebits) {
        return preReserveStepMaterials(specs, online, virtualDebits, true);
    }

    private List<ItemStack> preReserveStepMaterials(List<IngredientSpec> specs, ServerPlayer online,
                                                    @Nullable List<ItemStack> virtualDebits,
                                                    boolean logFailure) {
        if (currentStepIdx < steps.size()) {
            specs = MaterialLocks.narrowSpecs(steps.get(currentStepIdx).recipeId(),
                    specs, supplementalMaterialLocks);
        }
        int mark = ledger.reservationMark();
        int debitMark = virtualDebits == null ? 0 : virtualDebits.size();
        List<ItemStack> virtualSnapshot = StackCopies.excludingEmpty(virtualInventory);
        boolean reserved = false;
        try {
            List<ItemStack> materials = reserveStepMaterials(specs, online, virtualDebits, logFailure);
            reserved = materials != null;
            if (reserved && materialReservationFailureDetail != null) {
                materialReservationFailureDetail = null;
                machineStartFailureMessage = null;
            }
            return materials;
        } finally {
            if (!reserved) {
                ledger.cancelReservationsSince(mark);
                restoreVirtualSnapshot(virtualSnapshot);
                if (virtualDebits != null) virtualDebits.subList(debitMark, virtualDebits.size()).clear();
            }
        }
    }

    private List<ItemStack> reserveStepMaterials(List<IngredientSpec> specs, ServerPlayer online,
                                                @Nullable List<ItemStack> virtualDebits,
                                                boolean logFailure) {
        List<ItemStack> materials = new ArrayList<>();
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
                if (IngredientMatcher.test(spec.ingredient(), vi)) {
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

            if (needed > 0) {
                ItemStack reserved = reserveIngredient(
                        ledger, spec.ingredient(), needed, online);
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
                String ingredient = Arrays.stream(spec.ingredient().getItems())
                        .filter(stack -> !stack.isEmpty()).limit(8)
                        .map(stack -> String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())))
                        .collect(Collectors.joining(","));
                materialReservationFailureDetail = "Material reservation failed before machine start: recipe="
                        + steps.get(currentStepIdx).recipeId() + " inputCandidates(first8)=" + ingredient
                        + " spec=" + (materials.size() + 1) + "/" + specs.size()
                        + " required=" + spec.count() + " reserved=" + (spec.count() - needed)
                        + " unreserved=" + needed
                        + "; source reservations are all-or-nothing, unreserved is not a measured inventory shortage";
                machineStartFailureMessage = Component.translatable(
                        "rsi.generic.error.missing_materials",
                        CraftPacketUtils.describeIngredient(spec.ingredient()));
                if (logFailure) RSIntegrationMod.LOGGER.warn(ctx.format(materialReservationFailureDetail));
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
            ItemStack reserved = reserveIngredient(
                    targetLedger, spec.ingredient(), spec.count(), online);
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
                ? ResourceKey.create(Registries.DIMENSION, dimLoc)
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
        flatOperationSession = operationKernel.tryPrepare(
                new OperationId(craftId, new NodeId(Math.max(0, currentStepIdx)), 0),
                craftOperationBudget, key, capture);
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
                recordFinalDelivery(
                        s, safeGiveToPlayer(online, s));
            } else {
                boolean handled = insertOrDropAtSpawn(s);
                recordFinalDelivery(
                        s, handled && !InkFluidSupport.isToken(s));
            }
        }
    }

    private record OutputCollection(List<ItemStack> stacks,
                                    @Nullable OutputAccounting.Settlement settlement) {
        private OutputCollection {
            stacks = List.copyOf(stacks);
        }
    }

    private OutputCollection collectOutputsForSettlement(IBatchDelegate delegate,
                                                          ServerPlayer player,
                                                          List<ItemStack> capturedWorldOutputs) {
        OutputContract contract = delegate.outputContract();
        if (contract == null || contract.ports().isEmpty()) {
            ItemStack expectedWorldOutput = delegate.getExpectedOutput();
            List<ItemStack> collectedResults = delegate.collectAllResults(player);
            List<ItemStack> legacy = OutputAccounting.mergeLegacyOutputs(
                    capturedWorldOutputs, collectedResults, expectedWorldOutput);
            return new OutputCollection(legacy, null);
        }

        List<OutputAccounting.CollectedOutput> structured = new ArrayList<>(
                capturedWorldOutputRecords(contract, capturedWorldOutputs));
        List<OutputAccounting.CollectedOutput> collected = delegate.collectStructuredResults(player);
        if (collected != null) {
            for (OutputAccounting.CollectedOutput output : collected) {
                if (output != null) structured.add(output);
            }
        }
        List<ItemStack> stacks = structured.stream()
                .map(OutputAccounting.CollectedOutput::stack)
                .filter(stack -> stack != null && !stack.isEmpty())
                .map(ItemStack::copy)
                .toList();
        return new OutputCollection(stacks, OutputAccounting.assess(
                contract, Math.max(1, machineCount), structured));
    }

    private List<CollectedOutput> recordReportedResources(
            IBatchDelegate delegate
    ) {
        if (delegate == null) return List.of();
        List<CollectedOutput> recorded = new ArrayList<>();
        for (CollectedOutput output : delegate.drainReportedResources()) {
            if (output == null || output.amount() <= 0L) continue;
            reportedResources.add(output);
            recorded.add(output);
        }
        return List.copyOf(recorded);
    }

    private List<CollectedOutput> recordObservedProduction(
            IBatchDelegate delegate
    ) {
        if (delegate == null) return List.of();
        List<CollectedOutput> observed = new ArrayList<>();
        for (CollectedOutput output
                : delegate.drainObservedProduction()) {
            if (output == null || output.amount() <= 0L) continue;
            observed.add(output);
        }
        return List.copyOf(observed);
    }

    public List<CollectedOutput> reportedResources() {
        return List.copyOf(reportedResources);
    }

    static List<OutputAccounting.CollectedOutput> capturedWorldOutputRecords(
            OutputContract contract, List<ItemStack> captured) {
        if (captured == null || captured.isEmpty()) return List.of();
        OutputContract safeContract = contract == null ? OutputContract.none() : contract;
        List<OutputAccounting.CollectedOutput> outputs = new ArrayList<>();
        int unknownIndex = 0;
        for (ItemStack stack : captured) {
            if (stack == null || stack.isEmpty()) continue;
            List<OutputContract.Port> matches = safeContract.ports().stream()
                    .filter(port -> port.source() == OutputContract.Source.WORLD)
                    .filter(port -> MaterialMatcher.matchesOutputDeclaration(
                            MaterialKey.of(port.prototype()), stack))
                    .toList();
            String portId = matches.size() == 1
                    ? matches.get(0).portId() : "unmatched:world:" + unknownIndex++;
            outputs.add(new OutputAccounting.CollectedOutput(
                    portId, OutputContract.Source.WORLD, stack));
        }
        return List.copyOf(outputs);
    }

    private static String describeOutputSettlement(OutputAccounting.Settlement settlement) {
        return settlement.ports().stream()
                .filter(port -> !port.complete())
                .map(port -> port.port().portId() + " expected=" + port.expected()
                        + " actual=" + port.actual())
                .collect(Collectors.joining(", "));
    }

    private static long expectedStructuredOutputCount(OutputAccounting.Settlement settlement) {
        return settlement.ports().stream().mapToLong(OutputAccounting.PortSettlement::expected).sum();
    }

    private static long actualStructuredOutputCount(OutputAccounting.Settlement settlement) {
        return settlement.ports().stream().mapToLong(OutputAccounting.PortSettlement::actual).sum();
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

    private static void reportCraftedPrimaryOutputs(
            @Nullable ServerPlayer player, List<ItemStack> results,
            @Nullable IBatchDelegate.ExpectedProduction expected,
            @Nullable ItemStack expectedOutput) {
        if (player == null) return;
        for (ItemStack output : selectCraftedPrimaryOutputs(results, expected, expectedOutput)) {
            ExternalItemProgressBridge.enqueueCrafted(player, output);
        }
    }

    static List<ItemStack> selectCraftedPrimaryOutputs(
            List<ItemStack> results,
            @Nullable IBatchDelegate.ExpectedProduction expected,
            @Nullable ItemStack expectedOutput) {
        if (results == null || results.isEmpty()) return List.of();
        ItemStack prototype = expected != null ? expected.item() : expectedOutput;
        if (prototype == null || prototype.isEmpty()) return List.of();
        int remaining = expected == null ? Integer.MAX_VALUE : expected.count();
        if (remaining <= 0) return List.of();
        List<ItemStack> selected = new ArrayList<>();
        for (ItemStack result : results) {
            if (result != null && !result.isEmpty()
                    && IBatchDelegate.matchesProducedItem(result, prototype)) {
                int accepted = Math.min(remaining, result.getCount());
                if (accepted > 0) {
                    selected.add(result.copyWithCount(accepted));
                    remaining -= accepted;
                }
                if (remaining <= 0) break;
            }
        }
        return List.copyOf(selected);
    }

    private ItemStack currentStepOutputPrototype() {
        if (currentDelegate != null) {
            IBatchDelegate.ExpectedProduction expected = currentDelegate.getExpectedProduction();
            if (expected != null && expected.item() != null && !expected.item().isEmpty()) {
                return expected.item();
            }
            ItemStack expectedOutput = currentDelegate.getExpectedOutput();
            if (expectedOutput != null && !expectedOutput.isEmpty()) return expectedOutput;
        }
        if (targetOutput != null && !targetOutput.isEmpty()
                && currentStepIdx == steps.size() - 1) {
            return targetOutput;
        }
        if (currentStepIdx < 0 || currentStepIdx >= steps.size()) return ItemStack.EMPTY;
        ItemStack plannedOutput = steps.get(currentStepIdx).syntheticOutput();
        if (plannedOutput != null && !plannedOutput.isEmpty()) return plannedOutput;
        ServerLevel level = server.overworld();
        if (level == null) return ItemStack.EMPTY;
        Recipe<?> recipe = level.getRecipeManager()
                .byKey(steps.get(currentStepIdx).recipeId()).orElse(null);
        return recipe == null ? ItemStack.EMPTY
                : ModRecipeHandlers.tryGetResultItem(recipe, level.registryAccess());
    }

    static boolean isPrimaryGraphPublication(
            @Nullable CraftNode node, NodeOutputAccumulator.Publication publication) {
        if (node == null || publication == null) return false;
        return node.outputs().stream().anyMatch(output -> output.id().equals(publication.port())
                && output.kind() == OutputKind.PRIMARY);
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
                boolean delivered = leftover.isEmpty();
                if (!delivered) {
                    delivered = safeGiveToPlayer(online, leftover);
                }
                recordFinalDelivery(owed, delivered);
            } else {
                boolean handled = insertOrDropAtSpawn(owed.copy());
                if (!handled) {
                    // Drop throttle tripped (network full/absent, player offline,
                    // >20 drops this chain). insertOrDropAtSpawn already logged the
                    // discard. Do NOT clear the rest silently -surface it as CRITICAL
                    // so the docstring's "never silently lost" promise isn't a lie.
                    RSIntegrationMod.LOGGER.error(ctx.format(
                            "CRITICAL: drop throttle tripped during committed-product recovery; "
                            + "remaining owed products cannot be delivered for player {}"), playerId);
                    recordFinalDelivery(owed, false);
                } else {
                    recordFinalDelivery(
                            owed, !InkFluidSupport.isToken(owed));
                }
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
        if (InkFluidSupport.isToken(stack)) {
            CraftFluidRecovery.retainToEndpoint(
                    server, playerId, resolvePlayer(), stack, storageEndpoint);
            return true;
        }
        if (dropThrottleTripped) {
            RSIntegrationMod.LOGGER.warn("[RSI] Drop throttle tripped -discarding {} x{} for player {}",
                    ItemStackUtils.registryId(stack), stack.getCount(), playerId);
            return false;
        }
        dropsThisChain++;
        if (dropsThisChain > MAX_DROPS_PER_CHAIN) {
            dropThrottleTripped = true;
            RSIntegrationMod.LOGGER.warn("[RSI] Drop throttle tripped ({} drops) -discarding {} x{} and all future drops for player {}. Chain will abort.",
                    MAX_DROPS_PER_CHAIN, ItemStackUtils.registryId(stack), stack.getCount(), playerId);
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
                playerId, ItemStackUtils.registryId(stack), stack.getCount());
        }
        return true;
    }

    //  lifecycle

    private List<MagicResourceCost> magicCostsForPlan() {
        if (graph != null) {
            List<MagicResourceCost> graphCosts = graph.magicCosts();
            if (!graphCosts.isEmpty()) return graphCosts;
        }
        if (server.overworld() == null) return List.of();
        List<MagicResourceCost> costs = new ArrayList<>();
        RecipeManager recipes = server.overworld().getRecipeManager();
        for (CraftingResolver.ResolutionStep step : steps) {
            Recipe<?> recipe = recipes.byKey(step.recipeId()).orElse(null);
            for (MagicResourceCost cost : MagicResourceCostCatalog.costsFor(recipe)) {
                long total = cost.totalFor(step.executions());
                if (total <= 0L) continue;
                costs.add(new MagicResourceCost(cost.kind(), total));
            }
        }
        return MagicResourceCost.merge(costs);
    }

    private void attachMagicReservation(IBatchDelegate delegate) {
        if (delegate instanceof AbstractBatchDelegate abstractDelegate) {
            abstractDelegate.setMagicResourceTaskReservation(magicResourceTaskReservation);
        } else if (delegate instanceof ParallelCraftGroup group) {
            group.setMagicResourceTaskReservation(magicResourceTaskReservation);
        }
    }

    private void closeMagicReservation() {
        if (magicResourceTaskReservation == null) return;
        magicResourceTaskReservation.close();
        magicResourceTaskReservation = null;
    }

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
                if (InkFluidSupport.isToken(vi)) {
                    if (storageEndpoint == null || online == null) {
                        finalSettlementCursor--;
                        return false;
                    }
                    ItemStack remainder = insertIntoStorage(online, vi);
                    virtualInventory.set(finalSettlementCursor - 1, remainder);
                    if (!remainder.isEmpty()) {
                        finalSettlementCursor--;
                        return false;
                    }
                    recordFinalDelivery(vi, true);
                    continue;
                }
                if (storageEndpoint != null) {
                    boolean playerOutput = outputDestination == OutputDestination.PLAYER_INVENTORY
                            && (matchesFinalTarget(vi)
                            || (targetOutput != null && !targetOutput.isEmpty()
                            && vi.is(targetOutput.getItem())));
                    ItemStack leftover = playerOutput ? insertIntoPlayerInventory(online, vi) : vi.copy();
                    ItemStack rsCandidate = leftover.copy();
                    if (!leftover.isEmpty()) {
                        leftover = insertIntoStorage(online, rsCandidate);
                    }
                    if (!leftover.isEmpty()) {
                        recordFinalDelivery(
                                vi,
                                safeGiveToPlayer(online, leftover)
                        );
                    } else {
                        recordFinalDelivery(vi, true);
                    }
                } else {
                    // Keep the standalone path identical to the RS player's
                    // inventory destination: insert first, then deliver only
                    // a genuine remainder.  ItemHandlerHelper can trigger
                    // equipment/curio handlers for wearable outputs; handing
                    // the full stack to it after insertion leaves a client-side
                    // duplicate until the equipped slot is clicked.
                    ItemStack leftover = insertIntoPlayerInventory(online, vi.copy());
                    boolean delivered = leftover.isEmpty();
                    if (!delivered) {
                        delivered = safeGiveToPlayer(online, leftover);
                    }
                    recordFinalDelivery(vi, delivered);
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
        if (online != null) {
            ItemStack completedOutput = displayTarget();
            online.sendSystemMessage(CraftPacketUtils.craftCompletedMessage(
                    completedOutput, totalExecutionsForNotification()));
        }
        sendTerminalProgress(online);
        closeMagicReservation();
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
        return matchesFinalTarget(stack, graph, targetOutput);
    }

    static boolean matchesFinalTarget(ItemStack stack, @Nullable CraftPlanGraph graph,
                                      @Nullable ItemStack targetOutput) {
        if (stack.isEmpty()) return false;
        if (graph != null) {
            return matchesGraphFinalOutput(graph, stack);
        }
        if (targetOutput == null || targetOutput.isEmpty()) return false;
        return IngredientMatcher.matchesProducedOutput(targetOutput, stack);
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
     * Backend-neutral storage helpers. All execution paths use the resolved
     * endpoint and its session operations.
     */
    private boolean commitLedger(ExtractionLedger target, @Nullable ServerPlayer player) {
        if (player == null) return false;
        if (storageEndpoint != null) target.setStorageEndpoint(storageEndpoint);
        return target.commitToEndpoint(storageEndpoint, player);
    }

    private void refundCommitted(ExtractionLedger target, @Nullable ServerPlayer player) {
        if (storageEndpoint != null) target.setStorageEndpoint(storageEndpoint);
        target.refundCommittedToEndpoint(storageEndpoint, player);
    }

    private ItemStack reserveIngredient(ExtractionLedger target, Ingredient ingredient,
                                        int amount, ServerPlayer player) {
        if (amount <= 0 || ingredient.isEmpty()) return ItemStack.EMPTY;
        Map<String, CraftStorageEndpoint> candidates = new LinkedHashMap<>();
        if (storageEndpoint != null) {
            candidates.put(endpointKey(storageEndpoint), storageEndpoint);
        }
        for (CraftStorageEndpoint candidate :
                CraftPacketUtils.resolveEndpointCandidatesForCraft(player, null, null)) {
            candidates.putIfAbsent(endpointKey(candidate), candidate);
        }
        for (CraftStorageEndpoint candidate : candidates.values()) {
            ItemStack stored = target.reserveFromEndpoint(ingredient, amount, candidate, player);
            if (!stored.isEmpty()) return stored;
        }
        return target.reserveFromInventory(ingredient, amount, player);
    }

    private static String endpointKey(CraftStorageEndpoint endpoint) {
        try {
            return endpoint.session().reference().toString();
        } catch (RuntimeException ignored) {
            return "endpoint@" + System.identityHashCode(endpoint);
        }
    }

    private ItemStack reserveExact(ExtractionLedger target, ItemStack template,
                                   int amount, ServerPlayer player) {
        if (storageEndpoint != null) {
            return target.reserveExactAcrossNetworkAndInventory(template, amount, storageEndpoint, player);
        }
        return target.reserveExactAcrossNetworkAndInventory(
                template, amount, storageEndpoint, player);
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

    private int totalExecutionsForNotification() {
        long total = 0;
        for (var step : steps) {
            total += Math.max(1, step.executions());
            if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    /** Abort after a physical machine consumed inputs but its output escaped.
     * Refunding here would duplicate the escaped result. */
    private void abortWithoutRefund(String reason, Component userReason) {
        abortWithoutRefund(reason, userReason, CraftFailureCode.UNKNOWN);
    }

    private void abortWithoutRefund(String reason, Component userReason,
                                    CraftFailureCode code) {
        failureCode = code == null ? CraftFailureCode.UNKNOWN : code;
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
        abort(reason, userReason, CraftFailureCode.UNKNOWN);
    }

    public void abort(String reason, Component userReason, CraftFailureCode code) {
        failureCode = code == null ? CraftFailureCode.UNKNOWN : code;
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
                boolean capturedWorldOutput = OutputAccounting.hasCapturedExpectedProduction(
                        currentDelegate, capturedOutputSnapshot());
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
        List<ItemStack> captured = disarmOutputCapture();
        ItemStack expectedWorldOutput = currentDelegate.getExpectedOutput();
        List<ItemStack> collected = player == null
                ? List.of() : currentDelegate.collectAllResults(player);
        for (ItemStack result : OutputAccounting.mergeLegacyOutputs(
                captured, collected, expectedWorldOutput)) {
            if (result != null && !result.isEmpty()) addToVirtualInventory(result);
        }
        if (player == null) {
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

    @Nullable
    private static CancelReason cancellationReason(
            TerminationCoordinator.Cause cause
    ) {
        return switch (cause) {
            case CANCELLED -> CancelReason.PLAYER_REQUEST;
            case SERVER_STOP -> CancelReason.SERVER_STOP;
            case OFFLINE -> CancelReason.UNKNOWN;
            case FAILURE, INTERNAL_ERROR -> null;
        };
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
                CancelReason cancelReason = cancellationReason(cause);
                if (cancelReason != null) {
                    currentDelegate.prepareCancellation(online, cancelReason);
                }
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
        closeMagicReservation();
        fireOnDone();
    }

    /** Stop graph executor and clean up every running node runtime. */
    private void cleanupGraphNodes(@Nullable ServerPlayer player, String reason) {
        if (graphExecutor != null) {
            graphExecutor.stopScheduling();
        }
        for (GraphVanillaJob job : List.copyOf(graphVanillaJobs.values())) {
            RSIntegrationMod.LOGGER.debug(ctx.format(
                    "Recovering interrupted graph vanilla node {} after {}"),
                    job.nodeId, reason);
            recoverGraphVanillaJob(job);
        }
        for (CraftNodeRuntime runtime : List.copyOf(nodeRuntimes.values())) {
            if (runtime.failureReason() != null && !runtime.failureReason().isEmpty()) {
                graphFailureDetails.put(runtime.nodeId(), runtime.failureReason());
                graphFailureCodes.put(runtime.nodeId(), runtime.failureCode());
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
                new CraftStartedPacket(craftId, total, useGraphExecution, displayTarget()),
                online.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT);
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
        boolean codeKnown = embersCodeKnown(step);
        boolean useInference = EreAlchemyDelegateMode.shouldUseInference(
                step.modType().id(), step.inferMode(), codeKnown);
        return useInference ? step.modType().createInferDelegate() : createDelegate(step.modType());
    }

    /**
     * Embers 的配方代码一旦已经猜出（缓存或已保存），后续合成就不再是“推断中”，
     * 应与固定配方一样允许并行；只有真正未知的推断才保持独占。
     */
    private boolean embersCodeKnown(CraftingResolver.ResolutionStep step) {
        if (!step.inferMode() || !ModIds.ID_EMBERS_ALCHEMY.equals(step.modType().id())) {
            return false;
        }
        ServerPlayer player = resolvePlayer();
        ServerLevel dataLevel = player != null ? player.serverLevel() : server.overworld();
        KnownCodeSavedData savedData = KnownCodeSavedData.get(dataLevel);
        savedData.setWorldSeed(dataLevel.getSeed());
        return savedData.hasCode(step.recipeId().toString());
    }

    private IBatchDelegate createStepDelegate(CraftingResolver.ResolutionStep step,
                                               ModType machineType) {
        if (machineType == null || machineType == step.modType()) {
            return createStepDelegate(step);
        }
        // Compatible binding aliases describe the recipe using one type and the
        // physical worker using another. Inference is a recipe-type feature and
        // no current alias supports it, so the concrete machine delegate wins.
        return createDelegate(machineType);
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

    private boolean safeGiveToPlayer(ServerPlayer player, ItemStack stack) {
        return PlayerUtils.safeGiveToStorage(
                player, stack, storageEndpoint);
    }

    private void recordFinalDelivery(ItemStack stack, boolean delivered) {
        if (stack == null || stack.isEmpty()) return;
        addToInventory(
                delivered ? deliveredFinalOutputs : recoveryFinalOutputs,
                stack.copy()
        );
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
                ResourceLocation rl =
                        BuiltInRegistries.ITEM.getKey(stack.getItem());
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
            ResourceLocation rl =
                    BuiltInRegistries.ITEM.getKey(vi.getItem());
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
