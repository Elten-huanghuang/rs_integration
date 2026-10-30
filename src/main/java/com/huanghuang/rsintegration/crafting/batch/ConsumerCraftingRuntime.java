package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.consumer.ConsumerAck;
import com.huanghuang.rsintegration.api.consumer.ConsumerApiCodecs;
import com.huanghuang.rsintegration.api.consumer.ConsumerBatchOrderEntry;
import com.huanghuang.rsintegration.api.consumer.ConsumerBatchOrderReceipt;
import com.huanghuang.rsintegration.api.consumer.ConsumerBatchOrderRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerCancelResult;
import com.huanghuang.rsintegration.api.consumer.ConsumerCapabilities;
import com.huanghuang.rsintegration.api.consumer.ConsumerCraftability;
import com.huanghuang.rsintegration.api.consumer.ConsumerCraftabilityStatus;
import com.huanghuang.rsintegration.api.consumer.ConsumerDiagnostic;
import com.huanghuang.rsintegration.api.consumer.ConsumerDiagnosticCode;
import com.huanghuang.rsintegration.api.consumer.ConsumerExternalRequestId;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionPage;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionPageState;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionTicket;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionTicketId;
import com.huanghuang.rsintegration.api.consumer.ConsumerMachineSelector;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderId;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderReceipt;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderState;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderStatus;
import com.huanghuang.rsintegration.api.consumer.ConsumerOutputDestination;
import com.huanghuang.rsintegration.api.consumer.ConsumerOutputResult;
import com.huanghuang.rsintegration.api.consumer.ConsumerPlanId;
import com.huanghuang.rsintegration.api.consumer.ConsumerPlanningPreferences;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeKey;
import com.huanghuang.rsintegration.api.consumer.ConsumerRequestAmount;
import com.huanghuang.rsintegration.api.consumer.ConsumerResourceShortage;
import com.huanghuang.rsintegration.api.consumer.ConsumerResult;
import com.huanghuang.rsintegration.api.consumer.ConsumerStorageSelector;
import com.huanghuang.rsintegration.api.consumer.ConsumerStorageId;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.api.execution.FailureReason;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.CanonicalNbt;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.CraftingPlanningTimeoutException;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.ExecutionEquivalence;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.ProductionTarget;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.TerminationCoordinator;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.plan.MagicResourceCostCatalog;
import com.huanghuang.rsintegration.crafting.plan.RecipeChoice;
import com.huanghuang.rsintegration.crafting.planning.ForcedRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshotFactory;
import com.huanghuang.rsintegration.crafting.planning.PlanningStateValidator;
import com.huanghuang.rsintegration.crafting.planning.PurePlanAdapter;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageCapability;
import com.huanghuang.rsintegration.storage.StorageFluidSnapshot;
import com.huanghuang.rsintegration.storage.StoredFluid;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Consumer 检查会话、一次性计划租约和订单启动适配器。
 *
 * <p>该运行时不把 Consumer 状态写入现有预览缓存。ticket 共享同一份不可变
 * 配方图和库存快照，每个 plan 只保存请求差异与搜索结果。</p>
 */
@ApiStatus.Internal
public final class ConsumerCraftingRuntime {
    private static final ConsumerCraftingRuntime INSTANCE =
            new ConsumerCraftingRuntime();
    private static final int MAX_PLAN_ENTRIES = 8192;
    private static final int MAX_ORDER_ENTRIES = 4096;
    private static final int MAX_STORAGE_BINDINGS_PER_PLAYER = 64;
    private static final int PLANNING_THREADS = 2;
    private static final String JOURNAL_NAME =
            "rs_integration_consumer_orders";

    private final ConcurrentHashMap<ConsumerInspectionTicketId, Session> sessions =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InspectionRequestKey, ConsumerInspectionTicketId>
            inspectionRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ConsumerPlanId, PlanLease> plans =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ConsumerOrderId, OrderRecord> orders =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SubmitKey, SubmissionRecord> submissions =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BatchSubmitKey, BatchSubmissionRecord>
            batchSubmissions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CancelKey, CancelRecord> cancellations =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ConsumerStorageId, StorageBinding>
            storageBindings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<MinecraftServer, Journal> journals =
            new ConcurrentHashMap<>();
    private final ExecutorService planner = Executors.newFixedThreadPool(
            PLANNING_THREADS,
            runnable -> {
                Thread thread = new Thread(runnable, "RSI-Consumer-Planner");
                thread.setDaemon(true);
                return thread;
            });

    private ConsumerCraftingRuntime() {}

    public static ConsumerCraftingRuntime instance() {
        return INSTANCE;
    }

    public ConsumerStorageId registerStorage(
            ServerPlayer player,
            @Nullable StorageReference reference
    ) {
        long owned = storageBindings.values().stream()
                .filter(binding ->
                        binding.playerId().equals(player.getUUID()))
                .count();
        if (owned >= MAX_STORAGE_BINDINGS_PER_PLAYER) {
            storageBindings.entrySet().stream()
                    .filter(entry -> entry.getValue().playerId()
                            .equals(player.getUUID()))
                    .limit(owned - MAX_STORAGE_BINDINGS_PER_PLAYER + 1L)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(storageBindings::remove);
        }
        ConsumerStorageId id = new ConsumerStorageId(UUID.randomUUID());
        storageBindings.put(id, new StorageBinding(
                player.getUUID(),
                reference
        ));
        return id;
    }

    public ConsumerResult<ConsumerInspectionTicket> openInspection(
            ServerPlayer player,
            ConsumerInspectionRequest request,
            ConsumerCapabilities capabilities
    ) {
        ConsumerResult<ConsumerInspectionTicket> rejected =
                validateMainThread(player);
        if (rejected != null) return rejected;
        prunePlans(
                player.serverLevel().getGameTime(),
                capabilities.planTtlTicks()
        );
        if (!RecipeIndex.isReady(player.serverLevel())) {
            return reject(
                    ConsumerDiagnosticCode.CATALOG_NOT_READY,
                    "recipe catalog is still indexing",
                    true
            );
        }
        if (request.recipes().size() > capabilities.maxRecipesPerSession()) {
            return reject(
                    ConsumerDiagnosticCode.BATCH_LIMIT_EXCEEDED,
                    "inspection request exceeds the recipe limit",
                    false
            );
        }
        if (request.options().pageSize() > capabilities.maxPageSize()) {
            return reject(
                    ConsumerDiagnosticCode.PAGE_SIZE_EXCEEDED,
                    "inspection page exceeds the server limit",
                    false
            );
        }
        if (hasDuplicateRecipes(request.recipes())) {
            return reject(
                    ConsumerDiagnosticCode.INVALID_REQUEST,
                    "inspection recipes contain duplicate keys",
                    false
            );
        }
        int activeSessions = (int) sessions.values().stream()
                .filter(session -> !session.closed
                        && session.ticket.playerId().equals(player.getUUID()))
                .count();
        if (activeSessions >= capabilities.maxActiveSessionsPerPlayer()) {
            return reject(
                    ConsumerDiagnosticCode.SESSION_LIMIT,
                    "player has too many active inspection sessions",
                    true
            );
        }
        int globalSessions = (int) sessions.values().stream()
                .filter(session -> !session.closed)
                .count();
        if (globalSessions >= capabilities.maxActiveSessionsGlobal()) {
            return reject(
                    ConsumerDiagnosticCode.SESSION_LIMIT,
                    "server has too many active inspection sessions",
                    true
            );
        }

        InspectionRequestKey requestKey = new InspectionRequestKey(
                player.getUUID(),
                request.consumerId(),
                request.requestId().value()
        );
        ConsumerInspectionTicketId existingId =
                inspectionRequests.get(requestKey);
        if (existingId != null) {
            Session existing = sessions.get(existingId);
            if (existing == null || existing.closed) {
                return reject(
                        ConsumerDiagnosticCode.SESSION_EXPIRED,
                        "the idempotent inspection session has expired",
                        false
                );
            }
            if (!existing.request.equals(request)) {
                return reject(
                        ConsumerDiagnosticCode.IDEMPOTENCY_CONFLICT,
                        "inspection request id was reused with different content",
                        false
                );
            }
            return ConsumerResult.replay(existing.ticket);
        }

        CaptureResult capture = capture(
                player,
                request.recipes(),
                request.machineSelector(),
                request.storageSelector()
        );
        if (capture.failure != null) {
            return ConsumerResult.rejected(capture.failure);
        }
        if (request.outputDestination()
                == ConsumerOutputDestination.RS_NETWORK
                && capture.capture.storageReference == null) {
            return reject(
                    ConsumerDiagnosticCode.OUTPUT_UNAVAILABLE,
                    "requested network output has no selected storage endpoint",
                    true
            );
        }
        ConsumerInspectionTicket ticket = new ConsumerInspectionTicket(
                new ConsumerInspectionTicketId(UUID.randomUUID()),
                player.getUUID(),
                request.consumerId(),
                request.recipes().size(),
                request.options().pageSize(),
                capture.capture.revision
        );
        Session session = new Session(
                ticket,
                request,
                capture,
                player.serverLevel().getGameTime()
        );
        ConsumerInspectionTicketId previous =
                inspectionRequests.putIfAbsent(requestKey, ticket.ticketId());
        if (previous != null) {
            Session winner = sessions.get(previous);
            if (winner != null && winner.request.equals(request)) {
                return ConsumerResult.replay(winner.ticket);
            }
            return reject(
                    ConsumerDiagnosticCode.IDEMPOTENCY_CONFLICT,
                    "inspection request id was concurrently reused",
                    false
            );
        }
        sessions.put(ticket.ticketId(), session);
        pruneSessions(capabilities.maxActiveSessionsGlobal());
        return ConsumerResult.success(ticket);
    }

    public ConsumerResult<ConsumerInspectionPage> nextInspectionPage(
            ServerPlayer player,
            ConsumerInspectionTicketId ticketId,
            ConsumerCapabilities capabilities
    ) {
        ConsumerResult<ConsumerInspectionPage> rejected =
                validateMainThread(player);
        if (rejected != null) return rejected;
        prunePlans(
                player.serverLevel().getGameTime(),
                capabilities.planTtlTicks()
        );
        Session session = sessions.get(ticketId);
        ConsumerResult<ConsumerInspectionPage> invalid =
                validateSession(player, session, capabilities);
        if (invalid != null) return invalid;
        synchronized (session) {
            if (session.closed) {
                return reject(
                        ConsumerDiagnosticCode.SESSION_EXPIRED,
                        "inspection session is closed",
                        false
                );
            }
            if (session.capture.stale(player)) {
                session.close();
                return reject(
                        ConsumerDiagnosticCode.INSPECTION_SNAPSHOT_STALE,
                        "recipe, storage or machine binding changed during inspection",
                        true
                );
            }
            if (session.future == null) {
                if (session.nextOffset >= session.request.recipes().size()) {
                    return ConsumerResult.success(
                            new ConsumerInspectionPage(
                                    ticketId,
                                    session.nextOffset,
                                    ConsumerInspectionPageState.COMPLETE,
                                    null,
                                    session.ticket.snapshotRevision(),
                                    List.of()
                            )
                    );
                }
                int end = Math.min(
                        session.request.recipes().size(),
                        session.nextOffset + session.ticket.pageSize()
                );
                List<ConsumerRecipeKey> pageRecipes =
                        session.request.recipes()
                                .subList(session.nextOffset, end);
                session.futureOffset = session.nextOffset;
                session.futureEnd = end;
                session.future = CompletableFuture.supplyAsync(
                        () -> inspectPage(session, pageRecipes, capabilities),
                        planner
                );
            }
            if (!session.future.isDone()) {
                return ConsumerResult.success(
                        new ConsumerInspectionPage(
                                ticketId,
                                session.futureOffset,
                                session.future == null
                                        ? ConsumerInspectionPageState.PENDING
                                        : ConsumerInspectionPageState.RUNNING,
                                null,
                                session.ticket.snapshotRevision(),
                                List.of()
                        )
                );
            }
            List<ConsumerCraftability> results;
            try {
                PageComputation computation = session.future.join();
                results = resolveInspectionEntries(
                        player,
                        session,
                        computation.entries(),
                        capabilities
                );
                results = preflightPlans(
                        player, results);
            } catch (RuntimeException failure) {
                session.close();
                return reject(
                        ConsumerDiagnosticCode.INTERNAL_ERROR,
                        "inspection worker failed",
                        true
                );
            }
            int pageIndex = session.futureOffset;
            Integer nextPage = session.futureEnd
                    < session.request.recipes().size()
                    ? session.futureEnd : null;
            session.future = null;
            session.nextOffset = session.futureEnd;
            ConsumerInspectionPage page = new ConsumerInspectionPage(
                    ticketId,
                    pageIndex,
                    ConsumerInspectionPageState.COMPLETE,
                    nextPage,
                    session.ticket.snapshotRevision(),
                    results
            );
            return ConsumerResult.success(page);
        }
    }

    public ConsumerResult<ConsumerAck>
    closeInspection(
            ServerPlayer player,
            ConsumerInspectionTicketId ticketId,
            ConsumerCapabilities capabilities
    ) {
        ConsumerResult<ConsumerAck> rejected =
                validateMainThread(player);
        if (rejected != null) return rejected;
        Session session = sessions.get(ticketId);
        if (session == null
                || !session.ticket.playerId().equals(player.getUUID())) {
            return reject(
                    ConsumerDiagnosticCode.SESSION_NOT_FOUND,
                    "inspection session was not found",
                    false
            );
        }
        long age = player.serverLevel().getGameTime()
                - session.createdGameTime;
        if (age > capabilities.sessionTtlTicks()) {
            session.close();
            return reject(
                    ConsumerDiagnosticCode.SESSION_EXPIRED,
                    "inspection session has expired",
                    true
            );
        }
        session.close();
        return ConsumerResult.success(new ConsumerAck(true));
    }

    public ConsumerResult<ConsumerAck>
    discardPlan(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerPlanId planId
    ) {
        ConsumerResult<ConsumerAck> rejected =
                validateMainThread(player);
        if (rejected != null) return rejected;
        PlanLease plan = plans.get(planId);
        if (!ownedBy(plan, player, consumerId)) {
            return reject(
                    ConsumerDiagnosticCode.PLAN_NOT_FOUND,
                    "consumer plan was not found",
                    false
            );
        }
        if (plan.consumed.get()) {
            return reject(
                    ConsumerDiagnosticCode.PLAN_ALREADY_CONSUMED,
                    "consumer plan was already submitted",
                    false
            );
        }
        plans.remove(planId, plan);
        return ConsumerResult.success(new ConsumerAck(true));
    }

    public ConsumerResult<ConsumerOrderReceipt> submit(
            ServerPlayer player,
            ConsumerOrderRequest request,
            ConsumerCapabilities capabilities
    ) {
        ConsumerResult<ConsumerOrderReceipt> rejected =
                validateMainThread(player);
        if (rejected != null) return rejected;
        Journal journal = ensureJournal(player);
        SubmitKey key = new SubmitKey(
                player.getUUID(),
                request.consumerId(),
                request.externalRequestId().value()
        );
        SubmissionRecord previous = submissions.get(key);
        if (previous != null) {
            if (!previous.planId().equals(request.planId())) {
                return reject(
                        ConsumerDiagnosticCode.IDEMPOTENCY_CONFLICT,
                        "external request id was reused for a different plan",
                        false
                );
            }
            return replayOrder(previous.result());
        }
        if (activeOrderCount(player.getUUID())
                >= capabilities.maxConcurrentOrdersPerPlayer()) {
            ConsumerResult<ConsumerOrderReceipt> limited = reject(
                    ConsumerDiagnosticCode.ORDER_LIMIT_REACHED,
                    "player has too many active consumer orders",
                    true
            );
            SubmissionRecord record = new SubmissionRecord(
                    request.planId(), limited);
            submissions.put(key, record);
            journal.putSubmission(key, record);
            return limited;
        }

        PlanLease plan = plans.get(request.planId());
        if (plan == null || !plan.playerId.equals(player.getUUID())
                || !plan.consumerId.equals(request.consumerId())) {
            ConsumerResult<ConsumerOrderReceipt> missing = reject(
                    ConsumerDiagnosticCode.PLAN_NOT_FOUND,
                    "consumer plan was not found",
                    false
            );
            SubmissionRecord record = new SubmissionRecord(
                    request.planId(), missing);
            submissions.put(key, record);
            journal.putSubmission(key, record);
            return missing;
        }
        if (!plan.consumed.compareAndSet(false, true)) {
            return reject(
                    ConsumerDiagnosticCode.PLAN_ALREADY_CONSUMED,
                    "consumer plan was already used",
                    false
            );
        }
        long gameTime = player.serverLevel().getGameTime();
        if (gameTime - plan.createdGameTime > capabilities.planTtlTicks()) {
            ConsumerResult<ConsumerOrderReceipt> expired = reject(
                    ConsumerDiagnosticCode.PLAN_EXPIRED,
                    "consumer plan has expired",
                    true
            );
            SubmissionRecord record =
                    new SubmissionRecord(request.planId(), expired);
            submissions.put(key, record);
            journal.putSubmission(key, record);
            return expired;
        }
        if (!CraftPlanningRevision.isCurrent(plan.revision)) {
            ConsumerResult<ConsumerOrderReceipt> stale = reject(
                    ConsumerDiagnosticCode.PLAN_STALE,
                    "recipe revision changed before submission",
                    true
            );
            SubmissionRecord record =
                    new SubmissionRecord(request.planId(), stale);
            submissions.put(key, record);
            journal.putSubmission(key, record);
            return stale;
        }

        ResourceKey<Level> dimension = ResourceKey.create(
                Registries.DIMENSION, plan.dimension);
        CraftStorageEndpoint endpoint = plan.storageReference == null
                ? null
                : CraftStorageEndpoints.resolve(
                        plan.storageReference, player).orElse(null);
        if (plan.storageReference != null && endpoint == null) {
            ConsumerResult<ConsumerOrderReceipt> rejectedStorage = reject(
                    ConsumerDiagnosticCode.PLAN_STALE,
                    "selected storage endpoint is no longer available",
                    true
            );
            SubmissionRecord record = new SubmissionRecord(
                    request.planId(), rejectedStorage);
            submissions.put(key, record);
            journal.putSubmission(key, record);
            return rejectedStorage;
        }
        if (!PlanningStateValidator.revalidateForExecution(
                player,
                plan.snapshot,
                dimension,
                plan.position,
                plan.storageReference
        )) {
            ConsumerResult<ConsumerOrderReceipt> stale = reject(
                    ConsumerDiagnosticCode.PLAN_STALE,
                    "inventory, machine binding or network changed",
                    true
            );
            SubmissionRecord record =
                    new SubmissionRecord(request.planId(), stale);
            submissions.put(key, record);
            journal.putSubmission(key, record);
            return stale;
        }

        ConsumerOrderId orderId = new ConsumerOrderId(UUID.randomUUID());
        OrderRecord order = new OrderRecord(
                orderId,
                player.getUUID(),
                request.consumerId(),
                request.externalRequestId(),
                    plan.recipeId,
                plan.destination,
                player.serverLevel().getGameTime()
        );
        orders.put(orderId, order);
        journal.putOrder(order);
        try {
            AsyncCraftChain chain = CraftExecutionService.launchGraph(
                    player,
                    endpoint,
                    plan.executionGraph,
                    plan.recipeId,
                    plan.targetResource,
                    plan.targetOutput,
                    outputDestination(plan.destination),
                    running -> {
                        order.chain = running;
                        running.setTargetResource(
                                plan.targetResource);
                        running.setMachineSelection(
                                plan.recipeId,
                                plan.machineSelectionMode,
                                plan.dimension,
                                plan.position
                        );
                        running.onDone(() -> {
                            markOrderTerminal(order);
                            journal.putOrder(order);
                        });
                    }
            );
            order.chain = chain;
        } catch (RuntimeException failure) {
            order.state = ConsumerOrderState.FAILED;
            order.failureReason = FailureReason.UNKNOWN;
            journal.putOrder(order);
        }
        ConsumerOrderReceipt receipt = new ConsumerOrderReceipt(
                orderId, order.currentState());
        ConsumerResult<ConsumerOrderReceipt> result =
                ConsumerResult.success(receipt);
        SubmissionRecord record = new SubmissionRecord(
                request.planId(), result);
        submissions.put(key, record);
        journal.putSubmission(key, record);
        journal.putOrder(order);
        pruneOrders();
        return result;
    }

    public ConsumerResult<ConsumerBatchOrderReceipt> submitBatch(
            ServerPlayer player,
            ConsumerBatchOrderRequest request,
            ConsumerCapabilities capabilities
    ) {
        ConsumerResult<ConsumerBatchOrderReceipt> rejected =
                validateMainThread(player);
        if (rejected != null) return rejected;
        Journal journal = ensureJournal(player);
        if (request.orders().size() > capabilities.maxOrdersPerBatch()) {
            return reject(
                    ConsumerDiagnosticCode.BATCH_LIMIT_EXCEEDED,
                    "batch exceeds the server order limit",
                    false
            );
        }
        if (hasDuplicateOrderKeys(request.orders())) {
            return reject(
                    ConsumerDiagnosticCode.INVALID_REQUEST,
                    "batch contains duplicate plan or request ids",
                    false
            );
        }
        BatchSubmitKey key = new BatchSubmitKey(
                player.getUUID(),
                request.consumerId(),
                request.batchRequestId().value()
        );
        BatchSubmissionRecord previous = batchSubmissions.get(key);
        if (previous != null) {
            if (!previous.orders().equals(request.orders())) {
                return reject(
                        ConsumerDiagnosticCode.IDEMPOTENCY_CONFLICT,
                        "batch request id was reused with different content",
                        false
                );
            }
            return replayBatch(previous.result());
        }

        List<ConsumerBatchOrderEntry> entries = new ArrayList<>();
        for (ConsumerOrderRequest order : request.orders()) {
            entries.add(new ConsumerBatchOrderEntry(
                    order.externalRequestId(),
                    submit(player, order, capabilities)
            ));
        }
        ConsumerBatchOrderReceipt receipt = new ConsumerBatchOrderReceipt(
                request.batchRequestId(), entries);
        ConsumerResult<ConsumerBatchOrderReceipt> result =
                ConsumerResult.success(receipt);
        BatchSubmissionRecord record = new BatchSubmissionRecord(
                List.copyOf(request.orders()), result);
        batchSubmissions.put(key, record);
        journal.putBatch(key, record);
        return result;
    }

    public ConsumerResult<ConsumerOrderStatus> status(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerOrderId orderId
    ) {
        ConsumerResult<ConsumerOrderStatus> rejected =
                validateMainThread(player);
        if (rejected != null) return rejected;
        ensureJournal(player);
        OrderRecord order = orders.get(orderId);
        if (order == null || !order.playerId.equals(player.getUUID())
                || !order.consumerId.equals(consumerId)) {
            return reject(
                    ConsumerDiagnosticCode.ORDER_NOT_FOUND,
                    "consumer order was not found",
                    false
            );
        }
        return ConsumerResult.success(order.status());
    }

    public ConsumerResult<ConsumerCancelResult> cancel(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerOrderId orderId,
            ConsumerExternalRequestId requestId
    ) {
        ConsumerResult<ConsumerCancelResult> rejected =
                validateMainThread(player);
        if (rejected != null) return rejected;
        Journal journal = ensureJournal(player);
        OrderRecord order = orders.get(orderId);
        if (order == null || !order.playerId.equals(player.getUUID())
                || !order.consumerId.equals(consumerId)) {
            return reject(
                    ConsumerDiagnosticCode.ORDER_NOT_FOUND,
                    "consumer order was not found",
                    false
            );
        }
        CancelKey key = new CancelKey(
                player.getUUID(), consumerId, requestId.value());
        CancelRecord previous = cancellations.get(key);
        if (previous != null) {
            if (!previous.orderId().equals(orderId)) {
                return reject(
                        ConsumerDiagnosticCode.IDEMPOTENCY_CONFLICT,
                        "cancellation request id was reused for another order",
                        false
                );
            }
            return replay(previous.result());
        }
        if (order.currentState() == ConsumerOrderState.DELIVERING
                || order.currentState() == ConsumerOrderState.SUCCEEDED
                || order.currentState() == ConsumerOrderState.FAILED
                || order.currentState() == ConsumerOrderState.CANCELLED) {
            ConsumerResult<ConsumerCancelResult> notCancellable = reject(
                    ConsumerDiagnosticCode.ORDER_NOT_CANCELLABLE,
                    "consumer order is already delivering or terminal",
                    false
            );
            CancelRecord record =
                    new CancelRecord(orderId, notCancellable);
            cancellations.put(key, record);
            journal.putCancel(key, record);
            return notCancellable;
        }
        if (order.chain != null && !order.chain.isDone()) {
            order.chain.cancel(
                    "Consumer cancellation",
                    Component.literal("Consumer order cancelled"));
        }
        order.state = ConsumerOrderState.CANCELLED;
        ConsumerResult<ConsumerCancelResult> result =
                ConsumerResult.success(new ConsumerCancelResult(
                        orderId, ConsumerOrderState.CANCELLED));
        CancelRecord record = new CancelRecord(orderId, result);
        cancellations.put(key, record);
        journal.putCancel(key, record);
        journal.putOrder(order);
        return result;
    }

    public void clearPlayer(UUID playerId) {
        sessions.values().stream()
                .filter(session ->
                        session.ticket.playerId().equals(playerId))
                .forEach(Session::close);
        plans.entrySet().removeIf(entry ->
                entry.getValue().playerId.equals(playerId));
        storageBindings.entrySet().removeIf(entry ->
                entry.getValue().playerId().equals(playerId));
    }

    public void expireTransientState() {
        sessions.values().forEach(Session::close);
        sessions.clear();
        inspectionRequests.clear();
        plans.clear();
        storageBindings.clear();
    }

    private PageComputation inspectPage(
            Session session,
            List<ConsumerRecipeKey> recipes,
            ConsumerCapabilities capabilities
    ) {
        List<InspectionEntry> results =
                new ArrayList<>(recipes.size());
        for (ConsumerRecipeKey recipe : recipes) {
            results.add(inspectOne(session, recipe, capabilities));
        }
        return new PageComputation(List.copyOf(results));
    }

    private List<ConsumerCraftability> resolveInspectionEntries(
            ServerPlayer player,
            Session session,
            List<InspectionEntry> entries,
            ConsumerCapabilities capabilities
    ) {
        List<ConsumerCraftability> results =
                new ArrayList<>(entries.size());
        for (InspectionEntry entry : entries) {
            if (entry.result() != null) {
                results.add(entry.result());
            } else {
                results.add(resolveTyped(
                        player,
                        session,
                        Objects.requireNonNull(entry.typed()),
                        capabilities
                ));
            }
        }
        return List.copyOf(results);
    }

    private List<ConsumerCraftability> preflightPlans(
            ServerPlayer player,
            List<ConsumerCraftability> results
    ) {
        List<ConsumerCraftability> checked =
                new ArrayList<>(results.size());
        for (ConsumerCraftability result : results) {
            if (!result.canSubmit() || result.planId() == null) {
                checked.add(result);
                continue;
            }
            PlanLease plan = plans.get(result.planId());
            if (plan == null) {
                checked.add(unavailable(
                        result.recipe(),
                        ConsumerCraftabilityStatus.INTERNAL_ERROR,
                        ConsumerDiagnosticCode.INTERNAL_ERROR,
                        "consumer plan disappeared during inspection"
                ));
                continue;
            }
            ModType type = ModType.byId(plan.modTypeId);
            if (CraftExecutionPolicy.requiresBoundMachine(type)) {
                List<AltarBindingRegistry.BoundMachine> machines =
                        AltarBindingRegistry.getBoundMachinesForRecipe(
                                player, type, plan.recipeId);
                if (machines.isEmpty()
                        || AsyncCraftManager.getInstance()
                        .areAllMachinesLeased(machines, type.id())) {
                    plans.remove(plan.id, plan);
                    checked.add(unavailable(
                            result.recipe(),
                            ConsumerCraftabilityStatus.MACHINE_UNAVAILABLE,
                            ConsumerDiagnosticCode.MACHINE_UNAVAILABLE,
                            "all eligible machines are unavailable or leased"
                    ));
                    continue;
                }
            }
            if (plan.destination
                    == ConsumerOutputDestination.RS_NETWORK) {
                CraftStorageEndpoint endpoint = plan.storageReference == null
                        ? null
                        : CraftStorageEndpoints.resolve(
                                plan.storageReference, player).orElse(null);
                if (endpoint == null) {
                    plans.remove(plan.id, plan);
                    checked.add(unavailable(
                            result.recipe(),
                            ConsumerCraftabilityStatus.OUTPUT_UNAVAILABLE,
                            ConsumerDiagnosticCode.OUTPUT_UNAVAILABLE,
                            "selected network output is unavailable"
                    ));
                    continue;
                }
                if (plan.hasFluidOutputs
                        && !endpoint.session().supports(
                        StorageCapability.FLUID_STORAGE)) {
                    plans.remove(plan.id, plan);
                    checked.add(unavailable(
                            result.recipe(),
                            ConsumerCraftabilityStatus.OUTPUT_UNAVAILABLE,
                            ConsumerDiagnosticCode.OUTPUT_UNAVAILABLE,
                            "selected network has no fluid capacity"
                    ));
                    continue;
                }
                boolean accepted = plan.targetResource.kind()
                        == IntegrationResourceKey.ResourceKind.ITEM
                        ? itemOutputCanFit(
                                player,
                                endpoint,
                                Objects.requireNonNull(plan.targetOutput),
                                plan.plannedOutputUnits
                        )
                        : fluidOutputCanFit(
                                player,
                                endpoint,
                                plan.targetResource,
                                plan.plannedOutputUnits
                        );
                if (!accepted) {
                    plans.remove(plan.id, plan);
                    checked.add(unavailable(
                            result.recipe(),
                            ConsumerCraftabilityStatus.OUTPUT_UNAVAILABLE,
                            ConsumerDiagnosticCode.OUTPUT_UNAVAILABLE,
                            "selected network cannot accept the requested output"
                    ));
                    continue;
                }
            } else if (plan.hasFluidOutputs
                    || plan.targetResource.kind()
                    != IntegrationResourceKey.ResourceKind.ITEM
                    || plan.targetOutput == null
                    || !playerInventoryCanAccept(
                    player,
                    plan.targetOutput,
                    plan.plannedOutputUnits
            )) {
                plans.remove(plan.id, plan);
                checked.add(unavailable(
                        result.recipe(),
                        ConsumerCraftabilityStatus.OUTPUT_UNAVAILABLE,
                        ConsumerDiagnosticCode.OUTPUT_UNAVAILABLE,
                        "player inventory cannot accept the requested output"
                ));
                continue;
            }
            checked.add(result);
        }
        return List.copyOf(checked);
    }

    private static boolean itemOutputCanFit(
            ServerPlayer player,
            CraftStorageEndpoint endpoint,
            ItemStack target,
            long requested
    ) {
        ItemStack preview = target.copyWithCount(
                Math.max(1, SaturatingLongMath.clampToInt(requested)));
        return endpoint.insert(player, preview, true)
                .remainder().orElse(ItemStack.EMPTY).isEmpty();
    }

    private static boolean fluidOutputCanFit(
            ServerPlayer player,
            CraftStorageEndpoint endpoint,
            IntegrationResourceKey target,
            long requested
    ) {
        if (requested <= 0L
                || !endpoint.session().supports(
                StorageCapability.FLUID_STORAGE)) {
            return false;
        }
        long remaining = requested;
        while (remaining > 0L) {
            int chunk = (int) Math.min(
                    Integer.MAX_VALUE, remaining);
            FluidStack stack = fluidStack(target, chunk);
            if (stack.isEmpty()) return false;
            var result = endpoint.session().insertFluid(
                    player, stack, true);
            long accepted = result.transferredAmountExact()
                    .orElse(-1L);
            if (accepted != chunk) return false;
            remaining -= chunk;
        }
        return true;
    }

    private static FluidStack fluidStack(
            IntegrationResourceKey resource,
            long amount
    ) {
        if (resource.kind()
                != IntegrationResourceKey.ResourceKind.FLUID
                || amount <= 0L) {
            return FluidStack.EMPTY;
        }
        var fluid = ForgeRegistries.FLUIDS.getValue(resource.id());
        if (fluid == null) return FluidStack.EMPTY;
        FluidStack stack = new FluidStack(
                fluid,
                (int) Math.min(Integer.MAX_VALUE, amount)
        );
        CompoundTag identity = resource.identity();
        if (!identity.isEmpty()) stack.setTag(identity);
        return stack;
    }

    private static boolean playerInventoryCanAccept(
            ServerPlayer player,
            ItemStack target,
            long requested
    ) {
        if (requested <= 0L) return true;
        long remaining = requested;
        int stackLimit = Math.max(1, target.getMaxStackSize());
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) {
                remaining -= stackLimit;
            } else if (ItemStack.isSameItemSameTags(stack, target)) {
                remaining -= Math.max(
                        0, stack.getMaxStackSize() - stack.getCount());
            }
            if (remaining <= 0L) return true;
        }
        return false;
    }

    private InspectionEntry inspectOne(
            Session session,
            ConsumerRecipeKey key,
            ConsumerCapabilities capabilities
    ) {
        return InspectionEntry.typed(key);
    }

    private ConsumerCraftability resolveTyped(
            ServerPlayer player,
            Session session,
            ConsumerRecipeKey key,
            ConsumerCapabilities capabilities
    ) {
        Recipe<?> recipe = CraftRecipeResolver.resolve(
                player.serverLevel(), key.recipeId());
        if (recipe == null) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.RECIPE_NOT_FOUND,
                    ConsumerDiagnosticCode.RECIPE_NOT_FOUND,
                    "recipe was not found in the current recipe manager"
            );
        }
        ModRecipeHandlers.Claim claim =
                ModRecipeHandlers.claimFor(recipe);
        if (claim.ambiguous()) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.RECIPE_AMBIGUOUS,
                    ConsumerDiagnosticCode.RECIPE_CLAIM_AMBIGUOUS,
                    "recipe has multiple equal-priority handlers"
            );
        }
        ModRecipeHandler handler = claim.winner();
        if (handler == null) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.RECIPE_UNSUPPORTED,
                    ConsumerDiagnosticCode.RECIPE_UNSUPPORTED,
                    "recipe has no executable handler"
            );
        }
        ItemStack declaredTarget;
        try {
            declaredTarget = handler.getResultItem(
                    recipe,
                    player.serverLevel().registryAccess());
        } catch (RuntimeException failure) {
            declaredTarget = ItemStack.EMPTY;
        }
        IntegrationResourceKey declaredResource;
        try {
            declaredResource = handler.getPrimaryOutputResource(
                    recipe,
                    player.serverLevel().registryAccess());
        } catch (RuntimeException failure) {
            declaredResource = null;
        }
        if (declaredResource == null && !declaredTarget.isEmpty()) {
            declaredResource =
                    IntegrationResourceKey.item(declaredTarget);
        }
        IntegrationResourceKey targetResource =
                key.targetOutput() == null
                        ? declaredResource
                        : key.targetOutput();
        if (targetResource == null) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.RECIPE_UNSUPPORTED,
                    ConsumerDiagnosticCode.RECIPE_UNSUPPORTED,
                    "typed recipe has no stable resource target"
            );
        }
        ItemStack target = targetResource.kind()
                == IntegrationResourceKey.ResourceKind.ITEM
                ? itemStack(targetResource)
                : InkFluidSupport.tokenFor(targetResource, 1);
        // 与配方索引使用同一套细分规则：农夫乐事等处理器会声明模组级兜底类型，
        // 但具体配方仍应落到烹饪锅等可绑定机器，否则会被当成通用批处理而瞬间完成。
        ModType type = RecipeIndex.resolveIndexedModType(recipe, handler);
        if (targetResource.kind()
                == IntegrationResourceKey.ResourceKind.ITEM) {
            target = CraftMachineBindingSupport.routeRecipeOutput(
                    type, target);
            if (!target.isEmpty()) {
                targetResource =
                        IntegrationResourceKey.item(target);
            }
        }
        if (target.isEmpty()) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.RECIPE_UNSUPPORTED,
                    ConsumerDiagnosticCode.RECIPE_UNSUPPORTED,
                    "typed recipe target is not registered"
            );
        }
        List<IngredientSpec> declared = handler.getIngredients(recipe);
        if (declared == null) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.RECIPE_UNSUPPORTED,
                    ConsumerDiagnosticCode.RECIPE_UNSUPPORTED,
                    "typed recipe ingredients cannot be enumerated"
            );
        }
        List<IngredientSpec> contextual =
                handler.getPlanningIngredients(
                        recipe, player, declared);
        if (contextual == null) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.RECIPE_UNSUPPORTED,
                    ConsumerDiagnosticCode.RECIPE_UNSUPPORTED,
                    "machine context rejected the typed recipe inputs"
            );
        }
        ItemStack routedDeclaredTarget =
                CraftMachineBindingSupport.routeRecipeOutput(
                        type, declaredTarget);
        long declaredUnits;
        try {
            declaredUnits = handler.getPrimaryOutputUnits(
                    recipe,
                    player.serverLevel().registryAccess());
        } catch (RuntimeException failure) {
            declaredUnits = 0L;
        }
        long perOperationUnits = targetResource.kind()
                == IntegrationResourceKey.ResourceKind.ITEM
                ? Math.max(
                1L,
                routedDeclaredTarget.isEmpty()
                        ? target.getCount()
                        : routedDeclaredTarget.getCount())
                : Math.max(1L, declaredUnits);
        long requestedUnits = outputsRequestedCount(
                key, perOperationUnits);
        boolean targetedProduction =
                handler.requiresTargetedProduction(
                        recipe, targetResource);
        int executions;
        if (key.amount()
                instanceof ConsumerRequestAmount.Executions count) {
            executions = count.count();
        } else {
            long quantity = ((ConsumerRequestAmount.OutputUnits)
                    key.amount()).quantity();
            executions = SaturatingLongMath.clampToInt(
                    SaturatingLongMath.ceilDiv(
                            quantity,
                            perOperationUnits
                    )
            );
        }
        if (targetedProduction) {
            // 概率/动态产物按真实产物进度重试，不能把目标数量预先展开成
            // 固定执行次数，否则首轮就会超额备料。
            executions = 1;
        }
        if (executions <= 0
                || executions > capabilities.maxRepeatCount()) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.PLAN_LIMIT_REACHED,
                    ConsumerDiagnosticCode.BATCH_LIMIT_EXCEEDED,
                    "typed execution count exceeds the server limit"
            );
        }
        List<ScaledIngredientSpec> scaled =
                CraftIngredientPlanMath.scaleTerminalIngredientSpecs(
                        recipe,
                        contextual,
                        target,
                        executions
                );
        Map<String, ItemStack> materialLocks = new HashMap<>();
        key.preferences().materialLocks().forEach((lockKey, resource) -> {
            if (resource.kind()
                    == IntegrationResourceKey.ResourceKind.ITEM) {
                materialLocks.put(lockKey, itemStack(resource));
            }
        });
        Map<ResourceLocation, ResourceLocation> forced =
                forcedRecipes(key.preferences());
        if (targetResource.kind()
                == IntegrationResourceKey.ResourceKind.ITEM) {
            forced.put(
                    BuiltInRegistries.ITEM.getKey(target.getItem()),
                    key.recipeId());
        }
        PlanningPreferences preferences =
                planningPreferences(key.preferences());
        CraftStorageEndpoint endpoint = session.capture.storageReference
                == null
                ? null
                : CraftStorageEndpoints.resolve(
                        session.capture.storageReference,
                        player
                ).orElse(null);
        List<String> missing = new ArrayList<>();
        CraftPlanGraph graph;
        try {
            CraftingResolver.ActiveRootRecipe activeRoot =
                    new CraftingResolver.ActiveRootRecipe(
                            key.recipeId(), target);
            int timeout = 30_000;
            try {
                timeout = RSIntegrationConfig
                        .CRAFTING_TYPED_PREVIEW_TIMEOUT_MS.get();
            } catch (RuntimeException ignored) {
                // 配置尚未加载时使用有界默认值。
            }
            graph = CraftExecutionPolicy
                    .usesPhysicalMachineInputSlots(recipe)
                    ? CraftingResolver
                    .resolveMachineGraphForScaledSpecsWithTypes(
                            scaled,
                            session.capture.available,
                            player.serverLevel(),
                            player,
                            endpoint,
                            missing,
                            forced,
                            true,
                            timeout,
                            activeRoot,
                            materialLocks,
                            preferences
                    )
                    : CraftingResolver
                    .resolveGraphForScaledSpecsWithTypes(
                            scaled,
                            session.capture.available,
                            player.serverLevel(),
                            player,
                            endpoint,
                            missing,
                            forced,
                            true,
                            timeout,
                            activeRoot,
                            materialLocks,
                            preferences
                    );
        } catch (CraftingPlanningTimeoutException timeout) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.INTERNAL_ERROR,
                    ConsumerDiagnosticCode.INTERNAL_ERROR,
                    "typed planning timed out"
            );
        } catch (RuntimeException | LinkageError failure) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.INTERNAL_ERROR,
                    ConsumerDiagnosticCode.INTERNAL_ERROR,
                    "typed planning failed"
            );
        }
        if (graph == null) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.RECIPE_UNSUPPORTED,
                    ConsumerDiagnosticCode.RECIPE_UNSUPPORTED,
                    "typed planner produced no executable graph"
            );
        }
        if (!graph.unresolvedDemands().isEmpty() || !missing.isEmpty()) {
            IntegrationResourceKey fallbackResource =
                    targetResource;
            List<ConsumerResourceShortage> shortages =
                    graph.unresolvedDemands().stream()
                            .limit(64)
                            .map(demand -> {
                                ItemStack display =
                                        demand.displayHint();
                                if (display.isEmpty()
                                        && demand.ingredient()
                                        .getItems().length > 0) {
                                    display = demand.ingredient()
                                            .getItems()[0];
                                }
                                IntegrationResourceKey resource =
                                        display.isEmpty()
                                                ? fallbackResource
                                                : IntegrationResourceKey
                                                .item(display);
                                return new ConsumerResourceShortage(
                                        resource,
                                        demand.quantity(),
                                        0L
                                );
                            })
                            .toList();
            if (shortages.isEmpty()) {
                shortages = List.of(new ConsumerResourceShortage(
                        fallbackResource, 1L, 0L));
            }
            return new ConsumerCraftability(
                    key,
                    ConsumerCraftabilityStatus.MISSING_MATERIALS,
                    requestedQuantity(key),
                    0L,
                    shortages,
                    null,
                    List.of(publicDiagnostic(
                            IntegrationDiagnosticCode.MATERIAL_DECLARATION_INVALID,
                            "typed planning found missing materials"
                    ))
            );
        }
        CraftingResolver.ResolutionStep terminalStep =
                typedTerminalStep(
                        key.recipeId(),
                        recipe,
                        type,
                        executions,
                        targetResource,
                        target,
                        requestedUnits,
                        targetedProduction
                );
        CraftPlanGraph completeGraph;
        try {
            completeGraph = CraftTerminalSteps
                    .composeEquivalentTerminalGraph(
                            graph,
                            terminalStep,
                            target,
                            player.serverLevel()
                    );
        } catch (IllegalArgumentException | ArithmeticException failure) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.RECIPE_UNSUPPORTED,
                    ConsumerDiagnosticCode.RECIPE_UNSUPPORTED,
                    "typed recipe cannot be represented as a complete graph"
            );
        }
        if ((long) completeGraph.nodes().size()
                > capabilities.maxPlanNodes()) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.PLAN_LIMIT_REACHED,
                    ConsumerDiagnosticCode.PLAN_LIMIT_REACHED,
                    "typed plan exceeds the node limit"
            );
        }
        if (session.closed) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.INTERNAL_ERROR,
                    ConsumerDiagnosticCode.SESSION_EXPIRED,
                    "inspection session closed during typed planning"
            );
        }
        if (plans.size() >= MAX_PLAN_ENTRIES) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.PLAN_LIMIT_REACHED,
                    ConsumerDiagnosticCode.PLAN_LIMIT_REACHED,
                    "consumer plan registry is full"
            );
        }
        long requested = requestedUnits;
        ConsumerPlanId planId = new ConsumerPlanId(UUID.randomUUID());
        PlanningSnapshot snapshot = PlanningSnapshotFactory.capture(
                session.ticket.playerId(),
                0L,
                key.recipeId(),
                session.capture.available,
                forced,
                session.capture.graph,
                session.capture.networkFingerprint,
                session.capture.bindingFingerprint,
                session.capture.blockedOutputIds,
                true,
                preferences
        );
        PlanLease lease = new PlanLease(
                planId,
                session.ticket.playerId(),
                session.ticket.consumerId(),
                session.ticket.ticketId(),
                session.capture.revision,
                session.createdGameTime,
                snapshot,
                session.capture.graph,
                completeGraph,
                key.recipeId(),
                targetResource,
                targetResource.kind()
                        == IntegrationResourceKey.ResourceKind.ITEM
                        ? target : null,
                requested,
                requested,
                type.id(),
                session.capture.fluidOutputRecipeIds.contains(
                        key.recipeId()),
                session.capture.dimension,
                session.capture.position,
                machineSelectionMode(session.request.machineSelector()),
                session.request.outputDestination(),
                session.capture.storageReference
        );
        plans.put(planId, lease);
        return new ConsumerCraftability(
                key,
                ConsumerCraftabilityStatus.READY,
                requestedQuantity(key),
                key.amount()
                        instanceof ConsumerRequestAmount.OutputUnits
                        && !targetedProduction
                        ? requested : 0L,
                List.of(),
                planId,
                List.of()
        );
    }

    static CraftingResolver.ResolutionStep typedTerminalStep(
            ResourceLocation recipeId,
            Recipe<?> recipe,
            ModType type,
            int executions,
            ItemStack target,
            long requestedUnits,
            boolean targetedProduction
    ) {
        return typedTerminalStep(
                recipeId,
                recipe,
                type,
                executions,
                IntegrationResourceKey.item(target),
                target,
                requestedUnits,
                targetedProduction
        );
    }

    static CraftingResolver.ResolutionStep typedTerminalStep(
            ResourceLocation recipeId,
            Recipe<?> recipe,
            ModType type,
            int executions,
            IntegrationResourceKey targetResource,
            ItemStack target,
            long requestedUnits,
            boolean targetedProduction
    ) {
        ResourceLocation recipeTypeId = recipe.getType() == null
                ? recipeId
                : ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
        if (recipeTypeId == null) recipeTypeId = recipeId;
        ProductionTarget productionTarget = targetedProduction
                ? ProductionTarget.of(
                        targetResource, requestedUnits)
                : null;
        return new CraftingResolver.ResolutionStep(
                recipeId,
                type,
                recipeTypeId,
                List.of(),
                List.of(),
                false,
                executions,
                null,
                targetResource.kind()
                        == IntegrationResourceKey.ResourceKind.ITEM
                        ? target : null,
                productionTarget,
                MagicResourceCostCatalog.costsFor(recipe)
        );
    }

    static long outputsRequestedCount(
            ConsumerRecipeKey key,
            long perOperation
    ) {
        return key.amount() instanceof ConsumerRequestAmount.Executions count
                ? SaturatingLongMath.multiply(
                        Math.max(1, perOperation), count.count())
                : ((ConsumerRequestAmount.OutputUnits) key.amount())
                .quantity();
    }

    private static long plannedOutputQuantity(
            PureRecipePlanner.Result result,
            ImmutableRecipeGraph graph,
            MaterialRef target
    ) {
        long total = 0L;
        List<RecipeNode> nodes = graph.recipesByOutput()
                .getOrDefault(target, List.of());
        for (PureRecipePlanner.PlannedStep step : result.steps()) {
            RecipeNode node = nodes.stream()
                    .filter(candidate -> candidate.recipeId()
                            .equals(step.recipeId()))
                    .findFirst()
                    .orElse(null);
            if (node == null) continue;
            total = SaturatingLongMath.add(
                    total,
                    SaturatingLongMath.multiply(
                            node.outputCount(), step.batches())
            );
        }
        return total;
    }

    private static boolean graphHasFluidOutputs(
            ImmutableRecipeGraph graph
    ) {
        return graph.recipesByOutput().values().stream()
                .flatMap(List::stream)
                .anyMatch(RecipeNode::hasFluidOutputs);
    }

    private MaterialResolution material(
            ConsumerRecipeKey key,
            RecipeNode recipe
    ) {
        IntegrationResourceKey targetResource = key.targetOutput() == null
                ? resourceKey(recipe.output()) : key.targetOutput();
        MaterialRef material = key.targetOutput() == null
                ? recipe.output() : materialRef(targetResource);
        ItemStack targetOutput = targetResource.kind()
                == IntegrationResourceKey.ResourceKind.ITEM
                ? itemStack(targetResource) : null;
        long requested;
        if (key.amount()
                instanceof ConsumerRequestAmount.Executions executions) {
            requested = SaturatingLongMath.multiply(
                    recipe.outputCount(), executions.count());
        } else {
            requested = ((ConsumerRequestAmount.OutputUnits) key.amount())
                    .quantity();
        }
        return new MaterialResolution(
                targetResource, targetOutput, material, requested);
    }

    private static PlanningPreferences planningPreferences(
            ConsumerPlanningPreferences preferences
    ) {
        Map<String, RecipeChoice> recipeChoices = new HashMap<>();
        preferences.forcedRecipes().forEach((key, recipe) ->
                recipeChoices.put(key, new RecipeChoice.Normal(recipe)));
        Map<String, ItemStack> ingredientChoices = new HashMap<>();
        preferences.materialLocks().forEach((key, resource) -> {
            if (resource.kind()
                    == IntegrationResourceKey.ResourceKind.ITEM) {
                ingredientChoices.put(key, itemStack(resource));
            }
        });
        return new PlanningPreferences(
                recipeChoices,
                ingredientChoices,
                preferences.excludedRecipes()
        );
    }

    private static Map<ResourceLocation, ResourceLocation> forcedRecipes(
            ConsumerPlanningPreferences preferences
    ) {
        Map<ResourceLocation, ResourceLocation> forced = new HashMap<>();
        preferences.forcedRecipes().forEach((key, recipe) -> {
            ResourceLocation output = ResourceLocation.tryParse(key);
            if (output != null) forced.put(output, recipe);
        });
        return forced;
    }

    private static ItemStack itemStack(IntegrationResourceKey resource) {
        if (resource.kind()
                != IntegrationResourceKey.ResourceKind.ITEM) {
            throw new IllegalArgumentException("target is not an item");
        }
        Item item = BuiltInRegistries.ITEM.get(resource.id());
        if (item == Items.AIR) {
            throw new IllegalArgumentException("unknown target item");
        }
        ItemStack stack = new ItemStack(item);
        CompoundTag identity = resource.identity();
        if (!identity.isEmpty()) stack.setTag(identity);
        return stack;
    }

    private static MaterialRef materialRef(
            IntegrationResourceKey resource
    ) {
        return switch (resource.kind()) {
            case ITEM -> {
                ItemStack stack = itemStack(resource);
                yield ImmutableRecipeGraphProjector.material(
                        stack,
                        stack.hasTag()
                );
            }
            case FLUID -> MaterialRef.fluid(
                    resource.id(),
                    CanonicalNbt.exactSnbt(resource.identity())
            );
        };
    }

    private static ItemStack materialStack(MaterialRef material) {
        if (!material.isItem()) {
            throw new IllegalArgumentException("material is not an item");
        }
        Item item = BuiltInRegistries.ITEM.get(material.itemId());
        if (item == Items.AIR) {
            throw new IllegalArgumentException("unknown material item");
        }
        ItemStack stack = new ItemStack(item);
        if (!material.nbt().isEmpty()) {
            try {
                stack.setTag(TagParser.parseTag(material.nbt()));
            } catch (Exception exception) {
                throw new IllegalArgumentException(
                        "material identity is malformed", exception);
            }
        }
        return stack;
    }

    private static ConsumerCraftability missing(
            ConsumerRecipeKey key,
            long requested,
            PureRecipePlanner.Result result
    ) {
        List<ConsumerResourceShortage> shortages = result.missing().stream()
                .limit(64)
                .map(missing -> new ConsumerResourceShortage(
                        resourceKey(missing.alternatives().get(0)),
                        missing.count(),
                        0L
                ))
                .toList();
        return new ConsumerCraftability(
                key,
                ConsumerCraftabilityStatus.MISSING_MATERIALS,
                requestedQuantity(key),
                0L,
                shortages,
                null,
                List.of(publicDiagnostic(
                        IntegrationDiagnosticCode.MATERIAL_DECLARATION_INVALID,
                        "recursive planning found missing materials"
                ))
        );
    }

    private static ConsumerCraftability unavailable(
            ConsumerRecipeKey key,
            ConsumerCraftabilityStatus status,
            ConsumerDiagnosticCode code,
            String detail
    ) {
        return new ConsumerCraftability(
                key,
                status,
                requestedQuantity(key),
                0L,
                List.of(),
                null,
                List.of(publicDiagnostic(
                        integrationCode(code),
                        detail
                ))
        );
    }

    private static IntegrationDiagnostic publicDiagnostic(
            IntegrationDiagnosticCode code,
            String detail
    ) {
        return new IntegrationDiagnostic(
                code,
                IntegrationSeverity.ERROR,
                null,
                detail.isBlank()
                        ? Map.of()
                        : Map.of("detail", detail)
        );
    }

    private static IntegrationDiagnosticCode integrationCode(
            ConsumerDiagnosticCode code
    ) {
        return switch (code) {
            case MACHINE_UNAVAILABLE -> IntegrationDiagnosticCode.MACHINE_UNSUPPORTED;
            case MATERIAL_MISSING -> IntegrationDiagnosticCode.MATERIAL_DECLARATION_INVALID;
            case RECIPE_NOT_FOUND -> IntegrationDiagnosticCode.RECIPE_INSPECTION_FAILED;
            case RECIPE_UNSUPPORTED -> IntegrationDiagnosticCode.RECIPE_INSPECTION_FAILED;
            default -> IntegrationDiagnosticCode.UNKNOWN;
        };
    }

    private static IntegrationResourceKey resourceKey(MaterialRef material) {
        if (material.isItem()) {
            return IntegrationResourceKey.item(materialStack(material));
        }
        var fluid = ForgeRegistries.FLUIDS.getValue(material.resourceId());
        if (fluid == null) {
            throw new IllegalArgumentException("unknown material fluid");
        }
        FluidStack stack = new FluidStack(fluid, 1);
        if (!material.nbt().isEmpty()) {
            try {
                stack.setTag(TagParser.parseTag(material.nbt()));
            } catch (Exception exception) {
                throw new IllegalArgumentException(
                        "fluid identity is malformed", exception);
            }
        }
        return IntegrationResourceKey.fluid(stack);
    }

    private static long requestedQuantity(ConsumerRecipeKey key) {
        return key.amount() instanceof ConsumerRequestAmount.OutputUnits units
                ? units.quantity() : 0L;
    }

    private CaptureResult capture(
            ServerPlayer player,
            List<ConsumerRecipeKey> requestedRecipes,
            ConsumerMachineSelector machineSelector,
            ConsumerStorageSelector storageSelector
    ) {
        ResourceLocation terminalRecipeId =
                requestedRecipes.get(0).recipeId();
        ResourceLocation dimension;
        BlockPos position;
        MachineSelectionMode mode;
        if (machineSelector instanceof ConsumerMachineSelector.Exact exact) {
            dimension = exact.dimension();
            position = exact.position();
            mode = MachineSelectionMode.EXCLUSIVE;
        } else if (machineSelector
                instanceof ConsumerMachineSelector.Preferred preferred) {
            dimension = preferred.dimension();
            position = preferred.position();
            mode = preferred.allowAutomaticFallback()
                    ? MachineSelectionMode.PREFERRED
                    : MachineSelectionMode.EXCLUSIVE;
        } else {
            dimension = player.serverLevel().dimension().location();
            position = player.blockPosition();
            mode = MachineSelectionMode.AUTO;
        }

        Recipe<?> recipe = CraftRecipeResolver.resolve(
                player.serverLevel(), terminalRecipeId);
        ModType terminalType = recipe == null
                ? ModType.GENERIC : ModType.classifyRecipe(recipe);
        boolean exactMachineRequired =
                machineSelector instanceof ConsumerMachineSelector.Exact
                        || machineSelector
                        instanceof ConsumerMachineSelector.Preferred preferred
                        && !preferred.allowAutomaticFallback();
        if (exactMachineRequired && recipe != null
                && CraftExecutionPolicy.requiresBoundMachine(
                recipe, terminalType)
                && !AltarBindingRegistry.isBound(
                ResourceKey.create(Registries.DIMENSION, dimension),
                position,
                player)) {
            return new CaptureResult(
                    null,
                    ConsumerDiagnostic.error(
                            ConsumerDiagnosticCode.MACHINE_UNAVAILABLE,
                            "the selected machine is not bound or available",
                            true
                    )
            );
        }
        CraftStorageEndpoint endpoint = null;
        StorageReference explicitReference = null;
        if (storageSelector
                instanceof ConsumerStorageSelector.PlayerInventoryOnly) {
            endpoint = null;
        } else if (storageSelector
                instanceof ConsumerStorageSelector.Explicit explicit) {
            StorageBinding binding = storageBindings.get(explicit.storageId());
            if (binding == null
                    || !binding.playerId().equals(player.getUUID())) {
                return new CaptureResult(
                        null,
                        ConsumerDiagnostic.error(
                                ConsumerDiagnosticCode.STORAGE_UNAVAILABLE,
                                "explicit storage id is not valid for this player",
                                false
                        )
                );
            }
            explicitReference = binding.reference();
            if (explicitReference != null) {
                endpoint = CraftStorageEndpoints.resolve(
                        explicitReference, player).orElse(null);
                if (endpoint == null) {
                    return new CaptureResult(
                            null,
                            ConsumerDiagnostic.error(
                                    ConsumerDiagnosticCode.STORAGE_UNAVAILABLE,
                                    "explicit storage endpoint is unavailable",
                                    true
                            )
                    );
                }
                explicitReference = endpoint.session().reference();
            }
        } else {
            CraftPlanStorageService.Context context =
                    CraftPlanStorageService.resolvePlanContext(
                            player,
                            dimension,
                            position,
                            terminalType,
                            null
                    );
            if (context.failed()) {
                return new CaptureResult(
                        null,
                        ConsumerDiagnostic.error(
                                ConsumerDiagnosticCode.STORAGE_UNAVAILABLE,
                                "default storage endpoint is unavailable",
                                true
                        )
                );
            }
            endpoint = context.endpoint();
        }

        Map<CraftingResolver.StackKey, Long> available =
                MaterialSources.listAllAvailable(player, endpoint);
        Map<MaterialRef, Long> availableResources =
                new LinkedHashMap<>(ImmutableRecipeGraphProjector
                        .projectAvailability(available));
        if (endpoint != null
                && endpoint.session().supports(
                StorageCapability.FLUID_STORAGE)) {
            var fluidSnapshotResult =
                    endpoint.session().snapshotFluids(player);
            if (fluidSnapshotResult.successful()) {
                StorageFluidSnapshot fluidSnapshot =
                        fluidSnapshotResult.snapshotValue().orElseThrow();
                for (StoredFluid fluid : fluidSnapshot.fluids()) {
                    MaterialRef material = MaterialRef.fluid(
                            fluid.key().fluidId(),
                            CanonicalNbt.exactSnbt(fluid.key().tag())
                    );
                    availableResources.merge(
                            material,
                            fluid.amount(),
                            SaturatingLongMath::add
                    );
                }
            }
        }
        GenericCraftPacket.BindingAwareGraph bindingGraph =
                GenericCraftPacket.consumerBindingAwareGraph(
                        player,
                        ImmutableRecipeGraphProjector.capture(
                                player.serverLevel())
                );
        Set<ResourceLocation> projectableRecipeIds = new HashSet<>();
        Set<ResourceLocation> fluidOutputRecipeIds = new HashSet<>();
        for (ConsumerRecipeKey requested : requestedRecipes) {
            Recipe<?> requestedRecipe = CraftRecipeResolver.resolve(
                    player.serverLevel(), requested.recipeId());
            if (requestedRecipe != null
                    && (RecipeIndex.isBackgroundProjectable(
                    requestedRecipe)
                    || bindingGraph.graph().recipesById()
                    .containsKey(requested.recipeId()))) {
                projectableRecipeIds.add(requested.recipeId());
            }
            if (requestedRecipe != null) {
                ModRecipeHandler handler =
                        ModRecipeHandlers.claimFor(requestedRecipe).winner();
                if (handler != null && handler.hasFluidOutputs(
                        requestedRecipe)) {
                    fluidOutputRecipeIds.add(requested.recipeId());
                }
            }
        }
        String networkFingerprint =
                PlanningStateValidator.networkFingerprint(
                        endpoint == null
                                ? null : endpoint.session().reference(),
                        available
                );
        String bindingFingerprint =
                PlanningStateValidator.bindingFingerprint(
                        player,
                        ResourceKey.create(
                                Registries.DIMENSION, dimension),
                        position
                );
        return new CaptureResult(
                new Capture(
                        player.getUUID(),
                        CraftPlanningRevision.current(),
                        available,
                        availableResources,
                        bindingGraph.graph(),
                        bindingGraph.blockedOutputIds(),
                        projectableRecipeIds,
                        fluidOutputRecipeIds,
                        networkFingerprint,
                        bindingFingerprint,
                        dimension,
                        position.immutable(),
                        mode,
                        explicitReference != null
                                ? explicitReference
                                : endpoint == null
                                ? null
                                : endpoint.session().reference()
                ),
                null
        );
    }

    private static OutputDestination outputDestination(
            ConsumerOutputDestination destination
    ) {
        return switch (destination) {
            case RS_NETWORK -> OutputDestination.RS_NETWORK;
            case PLAYER_INVENTORY -> OutputDestination.PLAYER_INVENTORY;
        };
    }

    private static MachineSelectionMode machineSelectionMode(
            ConsumerMachineSelector selector
    ) {
        if (selector instanceof ConsumerMachineSelector.Exact) {
            return MachineSelectionMode.EXCLUSIVE;
        }
        if (selector instanceof ConsumerMachineSelector.Preferred preferred
                && !preferred.allowAutomaticFallback()) {
            return MachineSelectionMode.EXCLUSIVE;
        }
        if (selector instanceof ConsumerMachineSelector.Preferred) {
            return MachineSelectionMode.PREFERRED;
        }
        return MachineSelectionMode.AUTO;
    }

    private static boolean hasDuplicateRecipes(
            List<ConsumerRecipeKey> recipes
    ) {
        return new HashSet<>(recipes).size() != recipes.size();
    }

    private static boolean hasDuplicateOrderKeys(
            List<ConsumerOrderRequest> orders
    ) {
        Set<ConsumerPlanId> plans = new HashSet<>();
        Set<ConsumerExternalRequestId> requests = new HashSet<>();
        for (ConsumerOrderRequest order : orders) {
            if (!plans.add(order.planId())
                    || !requests.add(order.externalRequestId())) {
                return true;
            }
        }
        return false;
    }

    private int activeOrderCount(UUID playerId) {
        return (int) orders.values().stream()
                .filter(order -> order.playerId.equals(playerId))
                .filter(order -> !terminal(order.currentState()))
                .count();
    }

    private void markOrderTerminal(OrderRecord order) {
        if (order.chain == null) return;
        order.state = order.chain.isAborted()
                ? order.chain.terminationReport() != null
                && order.chain.terminationReport().cause()
                == TerminationCoordinator.Cause.CANCELLED
                        ? ConsumerOrderState.CANCELLED
                        : ConsumerOrderState.FAILED
                : ConsumerOrderState.SUCCEEDED;
        CraftProgressSnapshot snapshot = order.chain.nextStatusSnapshot();
        order.completedOperations = Math.max(
                order.completedOperations, snapshot.completedNodes());
        order.totalOperations = Math.max(
                order.totalOperations, snapshot.totalNodes());
        order.runningOperations = 0L;
        List<ConsumerOutputResult> delivered = observedOutputs(
                order.chain.deliveredFinalOutputs(),
                order.destination,
                true
        );
        delivered = concat(delivered, observedReportedOutputs(
                order.chain.reportedResources(),
                order.destination
        ));
        List<ConsumerOutputResult> recovery = observedOutputs(
                order.chain.recoveryFinalOutputs(),
                order.destination,
                false
        );
        order.outputs = List.copyOf(concat(delivered, recovery));
        if (order.state == ConsumerOrderState.FAILED) {
            order.failureReason = failureReason(snapshot.reason());
        }
    }

    static List<ConsumerOutputResult> observedOutputs(
            List<ItemStack> stacks,
            ConsumerOutputDestination destination,
            boolean delivered
    ) {
        Map<IntegrationResourceKey, Long> amounts = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            IntegrationResourceKey fluid =
                    InkFluidSupport.resource(stack);
            IntegrationResourceKey key = fluid == null
                    ? IntegrationResourceKey.item(stack)
                    : fluid;
            amounts.merge(
                    key,
                    (long) stack.getCount(),
                    SaturatingLongMath::add
            );
        }
        return amounts.entrySet().stream()
                .map(entry -> new ConsumerOutputResult(
                        entry.getKey(),
                        entry.getValue(),
                        destination,
                        delivered
                ))
                .toList();
    }

    static List<ConsumerOutputResult> observedReportedOutputs(
            List<CollectedOutput> outputs,
            ConsumerOutputDestination destination
    ) {
        Map<IntegrationResourceKey, Long> amounts =
                new LinkedHashMap<>();
        for (CollectedOutput output : outputs) {
            if (output == null || output.amount() <= 0L) continue;
            amounts.merge(
                    output.resource(),
                    output.amount(),
                    SaturatingLongMath::add
            );
        }
        return amounts.entrySet().stream()
                .map(entry -> new ConsumerOutputResult(
                        entry.getKey(),
                        entry.getValue(),
                        destination,
                        true
                ))
                .toList();
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        if (second.isEmpty()) return first;
        List<T> combined = new ArrayList<>(
                first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private static FailureReason failureReason(
            CraftProgressSnapshot.Reason reason
    ) {
        return switch (reason) {
            case MACHINE_BUSY, NO_BOUND_MACHINE -> FailureReason.MACHINE_INVALID;
            case OUTPUT_MISSING -> FailureReason.OUTPUT_INVALID;
            case PRODUCTION_TARGET_UNMET -> FailureReason.PRODUCTION_TARGET_UNMET;
            case TIMEOUT -> FailureReason.TIMEOUT;
            case PLAYER_OFFLINE -> FailureReason.PLAYER_OFFLINE;
            case INTERNAL_ERROR -> FailureReason.EXECUTION_EXCEPTION;
            default -> FailureReason.UNKNOWN;
        };
    }

    private static boolean terminal(ConsumerOrderState state) {
        return state == ConsumerOrderState.SUCCEEDED
                || state == ConsumerOrderState.FAILED
                || state == ConsumerOrderState.CANCELLED;
    }

    private static boolean ownedBy(
            @Nullable PlanLease plan,
            ServerPlayer player,
            ResourceLocation consumerId
    ) {
        return plan != null
                && plan.playerId.equals(player.getUUID())
                && plan.consumerId.equals(consumerId);
    }

    @Nullable
    private static <T> ConsumerResult<T> validateMainThread(
            ServerPlayer player
    ) {
        if (player == null || player.server == null) {
            return reject(
                    ConsumerDiagnosticCode.PLAYER_UNAVAILABLE,
                    "player is unavailable",
                    true
            );
        }
        if (!player.server.isSameThread()) {
            return reject(
                    ConsumerDiagnosticCode.WRONG_THREAD,
                    "consumer API must run on the server thread",
                    true
            );
        }
        if (player.hasDisconnected() || player.isRemoved()) {
            return reject(
                    ConsumerDiagnosticCode.PLAYER_UNAVAILABLE,
                    "player is unavailable",
                    true
            );
        }
        return null;
    }

    @Nullable
    private static ConsumerResult<ConsumerInspectionPage> validateSession(
            ServerPlayer player,
            @Nullable Session session,
            ConsumerCapabilities capabilities
    ) {
        if (session == null
                || !session.ticket.playerId().equals(player.getUUID())) {
            return reject(
                    ConsumerDiagnosticCode.SESSION_NOT_FOUND,
                    "inspection session was not found",
                    false
            );
        }
        long age = player.serverLevel().getGameTime()
                - session.createdGameTime;
        if (age > capabilities.sessionTtlTicks()) {
            session.close();
            return reject(
                    ConsumerDiagnosticCode.SESSION_EXPIRED,
                    "inspection session has expired",
                    true
            );
        }
        return null;
    }

    private static <T> ConsumerResult<T> reject(
            ConsumerDiagnosticCode code,
            String detail,
            boolean retryable
    ) {
        return ConsumerResult.rejected(
                ConsumerDiagnostic.error(code, detail, retryable));
    }

    private static <T> ConsumerResult<T> replay(
            ConsumerResult<T> previous
    ) {
        return new ConsumerResult<>(
                previous.value(),
                previous.diagnostics(),
                true
        );
    }

    private ConsumerResult<ConsumerOrderReceipt> replayOrder(
            ConsumerResult<ConsumerOrderReceipt> previous
    ) {
        if (!previous.successful()) return replay(previous);
        ConsumerOrderReceipt receipt = previous.value();
        OrderRecord order = orders.get(receipt.orderId());
        ConsumerOrderState state = order == null
                ? receipt.state() : order.currentState();
        return ConsumerResult.replay(new ConsumerOrderReceipt(
                receipt.orderId(), state));
    }

    private ConsumerResult<ConsumerBatchOrderReceipt> replayBatch(
            ConsumerResult<ConsumerBatchOrderReceipt> previous
    ) {
        if (!previous.successful()) return replay(previous);
        ConsumerBatchOrderReceipt batch = previous.value();
        List<ConsumerBatchOrderEntry> entries = batch.entries().stream()
                .map(entry -> new ConsumerBatchOrderEntry(
                        entry.externalRequestId(),
                        replayOrder(entry.result())
                ))
                .toList();
        return ConsumerResult.replay(new ConsumerBatchOrderReceipt(
                batch.batchRequestId(), entries));
    }

    private static long deadlineAfterMillis(long millis) {
        long now = System.nanoTime();
        long budget = Math.max(1L, millis) * 1_000_000L;
        long deadline = now + budget;
        return deadline < now ? Long.MAX_VALUE : deadline;
    }

    private Journal ensureJournal(ServerPlayer player) {
        MinecraftServer server = player.server;
        Journal journal = journals.computeIfAbsent(
                server, Journal::get);
        journal.mergeInto(this);
        return journal;
    }

    private void pruneSessions(int maximum) {
        if (sessions.size() <= maximum) return;
        sessions.values().stream()
                .filter(session -> session.closed)
                .limit(Math.max(0, sessions.size() - maximum))
                .forEach(session -> {
                    if (sessions.remove(
                            session.ticket.ticketId(), session)) {
                        inspectionRequests.entrySet().removeIf(entry ->
                                entry.getValue().equals(
                                        session.ticket.ticketId()));
                    }
                });
    }

    private void prunePlans(long gameTime, long ttlTicks) {
        plans.entrySet().removeIf(entry ->
                gameTime - entry.getValue().createdGameTime > ttlTicks);
    }

    private void pruneOrders() {
        if (orders.size() <= MAX_ORDER_ENTRIES) return;
        for (Map.Entry<ConsumerOrderId, OrderRecord> entry
                : List.copyOf(orders.entrySet())) {
            if (orders.size() <= MAX_ORDER_ENTRIES) break;
            OrderRecord order = entry.getValue();
            if (terminal(order.currentState())) {
                orders.remove(entry.getKey(), order);
            }
        }
    }

    private record MaterialResolution(
            IntegrationResourceKey targetResource,
            @Nullable ItemStack targetOutput,
            MaterialRef material,
            long requestedCount
    ) {
        private MaterialResolution {
            if (targetOutput != null) targetOutput = targetOutput.copy();
        }
    }

    private record InspectionEntry(
            @Nullable ConsumerCraftability result,
            @Nullable ConsumerRecipeKey typed
    ) {
        private InspectionEntry {
            if ((result == null) == (typed == null)) {
                throw new IllegalArgumentException(
                        "inspection entry requires exactly one outcome");
            }
        }

        private static InspectionEntry result(
                ConsumerCraftability result
        ) {
            return new InspectionEntry(result, null);
        }

        private static InspectionEntry typed(ConsumerRecipeKey key) {
            return new InspectionEntry(null, key);
        }
    }

    private record PageComputation(List<InspectionEntry> entries) {
        private PageComputation {
            entries = List.copyOf(entries);
        }
    }

    private record CaptureResult(
            @Nullable Capture capture,
            @Nullable ConsumerDiagnostic failure
    ) {}

    private record Capture(
            UUID playerId,
            long revision,
            Map<CraftingResolver.StackKey, Long> available,
            Map<MaterialRef, Long> availableResources,
            ImmutableRecipeGraph graph,
            Set<ResourceLocation> blockedOutputIds,
            Set<ResourceLocation> projectableRecipeIds,
            Set<ResourceLocation> fluidOutputRecipeIds,
            String networkFingerprint,
            String bindingFingerprint,
            ResourceLocation dimension,
            BlockPos position,
            MachineSelectionMode machineSelectionMode,
            @Nullable StorageReference
            storageReference
    ) {
        private Capture {
            available = Map.copyOf(available);
            availableResources = Map.copyOf(availableResources);
            blockedOutputIds = Set.copyOf(blockedOutputIds);
            projectableRecipeIds = Set.copyOf(projectableRecipeIds);
            fluidOutputRecipeIds = Set.copyOf(fluidOutputRecipeIds);
        }

        private boolean revisionCurrent() {
            return CraftPlanningRevision.isCurrent(revision);
        }

        private boolean stale(ServerPlayer player) {
            if (!revisionCurrent()) return true;
            if (storageReference != null
                    && CraftStorageEndpoints.resolve(
                    storageReference, player).isEmpty()) {
                return true;
            }
            String currentBinding =
                    PlanningStateValidator.bindingFingerprint(
                            player,
                            ResourceKey.create(
                                    Registries.DIMENSION, dimension),
                            position
                    );
            return !bindingFingerprint.equals(currentBinding);
        }
    }

    private static final class Session {
        private final ConsumerInspectionTicket ticket;
        private final ConsumerInspectionRequest request;
        private final Capture capture;
        private final long createdGameTime;
        private volatile boolean closed;
        private int nextOffset;
        private int futureOffset;
        private int futureEnd;
        private CompletableFuture<PageComputation> future;

        private Session(
                ConsumerInspectionTicket ticket,
                ConsumerInspectionRequest request,
                CaptureResult capture,
                long createdGameTime
        ) {
            this.ticket = ticket;
            this.request = request;
            this.capture = Objects.requireNonNull(capture.capture);
            this.createdGameTime = createdGameTime;
        }

        private void close() {
            closed = true;
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    private static final class PlanLease {
        private final ConsumerPlanId id;
        private final UUID playerId;
        private final ResourceLocation consumerId;
        private final ConsumerInspectionTicketId ticketId;
        private final long revision;
        private final long createdGameTime;
        private final PlanningSnapshot snapshot;
        private final ImmutableRecipeGraph planningGraph;
        private final CraftPlanGraph executionGraph;
        private final ResourceLocation recipeId;
        private final IntegrationResourceKey targetResource;
        @Nullable
        private final ItemStack targetOutput;
        private final long requestedOutputUnits;
        private final long plannedOutputUnits;
        private final String modTypeId;
        private final boolean hasFluidOutputs;
        private final ResourceLocation dimension;
        private final BlockPos position;
        private final MachineSelectionMode machineSelectionMode;
        private final ConsumerOutputDestination destination;
        private final StorageReference storageReference;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private PlanLease(
                ConsumerPlanId id,
                UUID playerId,
                ResourceLocation consumerId,
                ConsumerInspectionTicketId ticketId,
                long revision,
                long createdGameTime,
                PlanningSnapshot snapshot,
                ImmutableRecipeGraph planningGraph,
                CraftPlanGraph executionGraph,
                ResourceLocation recipeId,
                IntegrationResourceKey targetResource,
                @Nullable ItemStack targetOutput,
                long requestedOutputUnits,
                long plannedOutputUnits,
                String modTypeId,
                boolean hasFluidOutputs,
                ResourceLocation dimension,
                BlockPos position,
                MachineSelectionMode machineSelectionMode,
                ConsumerOutputDestination destination,
                @Nullable StorageReference
                        storageReference
        ) {
            this.id = id;
            this.playerId = playerId;
            this.consumerId = consumerId;
            this.ticketId = ticketId;
            this.revision = revision;
            this.createdGameTime = createdGameTime;
            this.snapshot = snapshot;
            this.planningGraph = planningGraph;
            this.executionGraph = Objects.requireNonNull(
                    executionGraph, "executionGraph");
            this.recipeId = recipeId;
            this.targetResource = targetResource;
            this.targetOutput = targetOutput == null
                    ? null : targetOutput.copy();
            this.requestedOutputUnits = requestedOutputUnits;
            this.plannedOutputUnits = plannedOutputUnits;
            this.modTypeId = modTypeId;
            this.hasFluidOutputs = hasFluidOutputs;
            this.dimension = dimension;
            this.position = position.immutable();
            this.machineSelectionMode = machineSelectionMode;
            this.destination = destination;
            this.storageReference = storageReference;
        }
    }

    static final class OrderRecord {
        private final ConsumerOrderId id;
        private final UUID playerId;
        private final ResourceLocation consumerId;
        private final ConsumerExternalRequestId externalRequestId;
        private final ResourceLocation recipeId;
        private final ConsumerOutputDestination destination;
        private final long createdGameTime;
        private volatile AsyncCraftChain chain;
        private volatile ConsumerOrderState state =
                ConsumerOrderState.ACCEPTED;
        private volatile long completedOperations;
        private volatile long totalOperations;
        private volatile long runningOperations;
        private volatile FailureReason failureReason;
        private volatile List<ConsumerOutputResult> outputs = List.of();

        OrderRecord(
                ConsumerOrderId id,
                UUID playerId,
                ResourceLocation consumerId,
                ConsumerExternalRequestId externalRequestId,
                ResourceLocation recipeId,
                ConsumerOutputDestination destination,
                long createdGameTime
        ) {
            this.id = id;
            this.playerId = playerId;
            this.consumerId = consumerId;
            this.externalRequestId = externalRequestId;
            this.recipeId = recipeId;
            this.destination = destination;
            this.createdGameTime = createdGameTime;
        }

        private ConsumerOrderState currentState() {
            AsyncCraftChain running = chain;
            if (running == null) return state;
            if (running.state() == AsyncCraftChain.State.PENDING) {
                return ConsumerOrderState.QUEUED;
            }
            if (running.state() == AsyncCraftChain.State.COMPLETED) {
                return ConsumerOrderState.SUCCEEDED;
            }
            if (running.state() == AsyncCraftChain.State.COMPLETING) {
                return ConsumerOrderState.DELIVERING;
            }
            if (running.state() == AsyncCraftChain.State.ABORTED) {
                TerminationCoordinator.Report report =
                        running.terminationReport();
                return report != null
                        && report.cause()
                        == TerminationCoordinator.Cause.CANCELLED
                        ? ConsumerOrderState.CANCELLED
                        : ConsumerOrderState.FAILED;
            }
            return ConsumerOrderState.RUNNING;
        }

        ConsumerOrderStatus status() {
            ConsumerOrderState current = currentState();
            long completed = completedOperations;
            long total = totalOperations;
            long running = runningOperations;
            FailureReason failure = failureReason;
            AsyncCraftChain currentChain = chain;
            if (currentChain != null) {
                CraftProgressSnapshot snapshot =
                        currentChain.nextStatusSnapshot();
                completed = snapshot.completedNodes();
                total = snapshot.totalNodes();
                running = snapshot.runningNodes();
                if (current == ConsumerOrderState.FAILED
                        && failure == null) {
                    failure = failureReason(snapshot.reason());
                }
            }
            return new ConsumerOrderStatus(
                    id,
                    consumerId,
                    recipeId,
                    current,
                    completed,
                    total,
                    running,
                    outputs,
                    failure,
                    current == ConsumerOrderState.FAILED
                            ? List.of(publicDiagnostic(
                            IntegrationDiagnosticCode.INTEGRATION_REPORTED_FAILURE,
                            "consumer order failed"
                    ))
                            : List.of()
            );
        }

        ConsumerOrderStatus persistedStatus() {
            return new ConsumerOrderStatus(
                    id,
                    consumerId,
                    recipeId,
                    currentState(),
                    completedOperations,
                    totalOperations,
                    runningOperations,
                    outputs,
                    failureReason,
                    state == ConsumerOrderState.FAILED
                            ? List.of(publicDiagnostic(
                            IntegrationDiagnosticCode.INTEGRATION_REPORTED_FAILURE,
                            "consumer order failed"
                    ))
                            : List.of()
            );
        }
    }

    record InspectionRequestKey(
            UUID playerId,
            ResourceLocation consumerId,
            UUID requestId
    ) {}

    record SubmitKey(
            UUID playerId,
            ResourceLocation consumerId,
            UUID requestId
    ) {}

    record BatchSubmitKey(
            UUID playerId,
            ResourceLocation consumerId,
            UUID requestId
    ) {}

    record CancelKey(
            UUID playerId,
            ResourceLocation consumerId,
            UUID requestId
    ) {}

    record SubmissionRecord(
            ConsumerPlanId planId,
            ConsumerResult<ConsumerOrderReceipt> result
    ) {}

    record BatchSubmissionRecord(
            List<ConsumerOrderRequest> orders,
            ConsumerResult<ConsumerBatchOrderReceipt> result
    ) {}

    record CancelRecord(
            ConsumerOrderId orderId,
            ConsumerResult<ConsumerCancelResult> result
    ) {}

    private record StorageBinding(
            UUID playerId,
            @Nullable StorageReference reference
    ) {}

    static final class Journal extends SavedData {
        private static final int FORMAT_VERSION = 1;
        private static final int MAX_RESULTS = 8192;
        final Map<ConsumerOrderId, OrderRecord> orders =
                new LinkedHashMap<>();
        final Map<SubmitKey, SubmissionRecord> submissions =
                new LinkedHashMap<>();
        final Map<BatchSubmitKey, BatchSubmissionRecord> batches =
                new LinkedHashMap<>();
        final Map<CancelKey, CancelRecord> cancellations =
                new LinkedHashMap<>();
        private boolean loadedIntoRuntime;

        Journal() {}

        Journal(CompoundTag tag) {
            if (tag.getInt("version") != FORMAT_VERSION) {
                return;
            }
            for (Tag value : tag.getList("orders", Tag.TAG_COMPOUND)) {
                try {
                    OrderRecord order = readOrder((CompoundTag) value);
                    if (order != null) orders.put(order.id, order);
                } catch (RuntimeException ignored) {
                    // 单条损坏记录不能阻止其他订单恢复。
                }
            }
            for (Tag value : tag.getList(
                    "submissions", Tag.TAG_COMPOUND)) {
                try {
                    SubmissionEntry entry =
                            readSubmission((CompoundTag) value);
                    if (entry != null) {
                        submissions.put(entry.key(), entry.record());
                    }
                } catch (RuntimeException ignored) {
                    // 忽略损坏的幂等记录。
                }
            }
            for (Tag value : tag.getList("batches", Tag.TAG_COMPOUND)) {
                try {
                    BatchEntry entry = readBatch((CompoundTag) value);
                    if (entry != null) {
                        batches.put(entry.key(), entry.record());
                    }
                } catch (RuntimeException ignored) {
                    // 忽略损坏的批级幂等记录。
                }
            }
            for (Tag value : tag.getList("cancels", Tag.TAG_COMPOUND)) {
                try {
                    CancelEntry entry = readCancel((CompoundTag) value);
                    if (entry != null) {
                        cancellations.put(entry.key(), entry.record());
                    }
                } catch (RuntimeException ignored) {
                    // 忽略损坏的取消幂等记录。
                }
            }
        }

        private static Journal get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(
                    Journal::new,
                    Journal::new,
                    JOURNAL_NAME
            );
        }

        private synchronized void mergeInto(
                ConsumerCraftingRuntime runtime
        ) {
            if (loadedIntoRuntime) return;
            orders.forEach((id, order) ->
                    runtime.orders.putIfAbsent(id, order));
            submissions.forEach((key, record) ->
                    runtime.submissions.putIfAbsent(key, record));
            batches.forEach((key, record) ->
                    runtime.batchSubmissions.putIfAbsent(key, record));
            cancellations.forEach((key, record) ->
                    runtime.cancellations.putIfAbsent(key, record));
            loadedIntoRuntime = true;
        }

        synchronized void putOrder(OrderRecord order) {
            orders.put(order.id, order);
            while (orders.size() > MAX_RESULTS) {
                ConsumerOrderId removable = orders.values().stream()
                        .filter(candidate ->
                                terminal(candidate.currentState()))
                        .map(candidate -> candidate.id)
                        .findFirst().orElse(null);
                if (removable == null) break;
                orders.remove(removable);
            }
            setDirty();
        }

        synchronized void putSubmission(
                SubmitKey key,
                SubmissionRecord record
        ) {
            submissions.put(key, record);
            trimOldest(submissions);
            setDirty();
        }

        synchronized void putBatch(
                BatchSubmitKey key,
                BatchSubmissionRecord record
        ) {
            batches.put(key, record);
            trimOldest(batches);
            setDirty();
        }

        synchronized void putCancel(
                CancelKey key,
                CancelRecord record
        ) {
            cancellations.put(key, record);
            trimOldest(cancellations);
            setDirty();
        }

        private static void trimOldest(Map<?, ?> values) {
            while (values.size() > MAX_RESULTS) {
                Object oldest = values.keySet().iterator().next();
                values.remove(oldest);
            }
        }

        @Override
        public synchronized CompoundTag save(CompoundTag tag) {
            tag.putInt("version", FORMAT_VERSION);
            ListTag storedOrders = new ListTag();
            orders.values().stream()
                    .limit(MAX_RESULTS)
                    .map(Journal::writeOrder)
                    .forEach(storedOrders::add);
            tag.put("orders", storedOrders);

            ListTag storedSubmissions = new ListTag();
            submissions.entrySet().stream()
                    .limit(MAX_RESULTS)
                    .map(Journal::writeSubmission)
                    .forEach(storedSubmissions::add);
            tag.put("submissions", storedSubmissions);

            ListTag storedBatches = new ListTag();
            batches.entrySet().stream()
                    .limit(MAX_RESULTS)
                    .map(Journal::writeBatch)
                    .forEach(storedBatches::add);
            tag.put("batches", storedBatches);

            ListTag storedCancels = new ListTag();
            cancellations.entrySet().stream()
                    .limit(MAX_RESULTS)
                    .map(Journal::writeCancel)
                    .forEach(storedCancels::add);
            tag.put("cancels", storedCancels);
            return tag;
        }

        private static CompoundTag writeOrder(OrderRecord order) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("id", order.id.value());
            tag.putUUID("player", order.playerId);
            tag.putString("consumer", order.consumerId.toString());
            tag.putUUID("external", order.externalRequestId.value());
            tag.putString(
                    "destination", order.destination.name());
            tag.putLong("created", order.createdGameTime);
            tag.putByteArray("status", encode(buffer ->
                    ConsumerApiCodecs.writeOrderStatus(
                            buffer, order.persistedStatus())));
            return tag;
        }

        @Nullable
        private static OrderRecord readOrder(CompoundTag tag) {
            if (!tag.hasUUID("id") || !tag.hasUUID("player")
                    || !tag.hasUUID("external")
                    || !tag.contains("status", Tag.TAG_BYTE_ARRAY)) {
                return null;
            }
            ResourceLocation consumer = ResourceLocation.tryParse(
                    tag.getString("consumer"));
            if (consumer == null) return null;
            ConsumerOrderStatus status = decode(
                    tag.getByteArray("status"),
                    ConsumerApiCodecs::readOrderStatus
            );
            ConsumerOrderState state = status.terminal()
                    ? status.state()
                    : ConsumerOrderState.FAILED;
            FailureReason failure = status.terminal()
                    ? status.failureReason()
                    : FailureReason.UNKNOWN;
            ConsumerOutputDestination destination;
            try {
                destination = ConsumerOutputDestination.valueOf(
                        tag.getString("destination"));
            } catch (IllegalArgumentException exception) {
                destination = ConsumerOutputDestination.RS_NETWORK;
            }
            OrderRecord order = new OrderRecord(
                    status.orderId(),
                    tag.getUUID("player"),
                    consumer,
                    new ConsumerExternalRequestId(
                            tag.getUUID("external")),
                    status.recipeId(),
                    destination,
                    tag.getLong("created")
            );
            order.state = state;
            order.completedOperations = status.completedOperations();
            order.totalOperations = status.totalOperations();
            order.runningOperations = status.runningOperations();
            order.failureReason = failure;
            order.outputs = status.outputs();
            return order;
        }

        private static CompoundTag writeSubmission(
                Map.Entry<SubmitKey, SubmissionRecord> entry
        ) {
            CompoundTag tag = writeSubmitKey(entry.getKey());
            tag.putUUID("plan", entry.getValue().planId().value());
            tag.putByteArray("result", encode(buffer ->
                    ConsumerApiCodecs.writeResult(
                            buffer,
                            entry.getValue().result(),
                            ConsumerApiCodecs::writeOrderReceipt
                    )));
            return tag;
        }

        @Nullable
        private static SubmissionEntry readSubmission(CompoundTag tag) {
            SubmitKey key = readSubmitKey(tag);
            if (key == null || !tag.hasUUID("plan")
                    || !tag.contains("result", Tag.TAG_BYTE_ARRAY)) {
                return null;
            }
            ConsumerResult<ConsumerOrderReceipt> result = decode(
                    tag.getByteArray("result"),
                    buffer -> ConsumerApiCodecs.readResult(
                            buffer, ConsumerApiCodecs::readOrderReceipt)
            );
            return new SubmissionEntry(
                    key,
                    new SubmissionRecord(
                            new ConsumerPlanId(tag.getUUID("plan")),
                            result
                    )
            );
        }

        private static CompoundTag writeBatch(
                Map.Entry<BatchSubmitKey, BatchSubmissionRecord> entry
        ) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("player", entry.getKey().playerId());
            tag.putString(
                    "consumer", entry.getKey().consumerId().toString());
            tag.putUUID("request", entry.getKey().requestId());
            tag.putByteArray("orders", encode(buffer ->
                    writeOrderRequests(
                            buffer, entry.getValue().orders())));
            tag.putByteArray("result", encode(buffer ->
                    ConsumerApiCodecs.writeResult(
                            buffer,
                            entry.getValue().result(),
                            ConsumerApiCodecs::writeBatchOrderReceipt
                    )));
            return tag;
        }

        @Nullable
        private static BatchEntry readBatch(CompoundTag tag) {
            if (!tag.hasUUID("player") || !tag.hasUUID("request")
                    || !tag.contains("orders", Tag.TAG_BYTE_ARRAY)
                    || !tag.contains("result", Tag.TAG_BYTE_ARRAY)) {
                return null;
            }
            ResourceLocation consumer = ResourceLocation.tryParse(
                    tag.getString("consumer"));
            if (consumer == null) return null;
            List<ConsumerOrderRequest> requests = decode(
                    tag.getByteArray("orders"),
                    Journal::readOrderRequests
            );
            ConsumerResult<ConsumerBatchOrderReceipt> result = decode(
                    tag.getByteArray("result"),
                    buffer -> ConsumerApiCodecs.readResult(
                            buffer,
                            ConsumerApiCodecs::readBatchOrderReceipt
                    )
            );
            return new BatchEntry(
                    new BatchSubmitKey(
                            tag.getUUID("player"),
                            consumer,
                            tag.getUUID("request")
                    ),
                    new BatchSubmissionRecord(requests, result)
            );
        }

        private static CompoundTag writeCancel(
                Map.Entry<CancelKey, CancelRecord> entry
        ) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("player", entry.getKey().playerId());
            tag.putString(
                    "consumer", entry.getKey().consumerId().toString());
            tag.putUUID("request", entry.getKey().requestId());
            tag.putUUID(
                    "order", entry.getValue().orderId().value());
            tag.putByteArray("result", encode(buffer ->
                    ConsumerApiCodecs.writeResult(
                            buffer,
                            entry.getValue().result(),
                            ConsumerApiCodecs::writeCancelResult
                    )));
            return tag;
        }

        @Nullable
        private static CancelEntry readCancel(CompoundTag tag) {
            if (!tag.hasUUID("player") || !tag.hasUUID("request")
                    || !tag.hasUUID("order")
                    || !tag.contains("result", Tag.TAG_BYTE_ARRAY)) {
                return null;
            }
            ResourceLocation consumer = ResourceLocation.tryParse(
                    tag.getString("consumer"));
            if (consumer == null) return null;
            ConsumerResult<ConsumerCancelResult> result = decode(
                    tag.getByteArray("result"),
                    buffer -> ConsumerApiCodecs.readResult(
                            buffer, ConsumerApiCodecs::readCancelResult)
            );
            return new CancelEntry(
                    new CancelKey(
                            tag.getUUID("player"),
                            consumer,
                            tag.getUUID("request")
                    ),
                    new CancelRecord(
                            new ConsumerOrderId(tag.getUUID("order")),
                            result
                    )
            );
        }

        private static CompoundTag writeSubmitKey(SubmitKey key) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("player", key.playerId());
            tag.putString(
                    "consumer", key.consumerId().toString());
            tag.putUUID("request", key.requestId());
            return tag;
        }

        @Nullable
        private static SubmitKey readSubmitKey(CompoundTag tag) {
            if (!tag.hasUUID("player") || !tag.hasUUID("request")) {
                return null;
            }
            ResourceLocation consumer = ResourceLocation.tryParse(
                    tag.getString("consumer"));
            return consumer == null ? null : new SubmitKey(
                    tag.getUUID("player"),
                    consumer,
                    tag.getUUID("request")
            );
        }

        private static void writeOrderRequests(
                FriendlyByteBuf buffer,
                List<ConsumerOrderRequest> requests
        ) {
            buffer.writeVarInt(requests.size());
            for (ConsumerOrderRequest request : requests) {
                ConsumerApiCodecs.writeOrderRequest(buffer, request);
            }
        }

        private static List<ConsumerOrderRequest> readOrderRequests(
                FriendlyByteBuf buffer
        ) {
            int size = buffer.readVarInt();
            if (size < 0 || size > 64) {
                throw new IllegalArgumentException(
                        "invalid persisted batch size");
            }
            List<ConsumerOrderRequest> requests =
                    new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                requests.add(ConsumerApiCodecs.readOrderRequest(buffer));
            }
            return List.copyOf(requests);
        }

        private static byte[] encode(BinaryWriter writer) {
            FriendlyByteBuf buffer =
                    new FriendlyByteBuf(Unpooled.buffer());
            try {
                writer.write(buffer);
                return ByteBufUtil.getBytes(
                        buffer, buffer.readerIndex(),
                        buffer.readableBytes(), false);
            } finally {
                buffer.release();
            }
        }

        private static <T> T decode(
                byte[] payload,
                Function<FriendlyByteBuf, T> reader
        ) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(
                    Unpooled.wrappedBuffer(payload));
            try {
                return reader.apply(buffer);
            } finally {
                buffer.release();
            }
        }

        @FunctionalInterface
        private interface BinaryWriter {
            void write(FriendlyByteBuf buffer);
        }

        private record SubmissionEntry(
                SubmitKey key,
                SubmissionRecord record
        ) {}

        private record BatchEntry(
                BatchSubmitKey key,
                BatchSubmissionRecord record
        ) {}

        private record CancelEntry(
                CancelKey key,
                CancelRecord record
        ) {}
    }
}
