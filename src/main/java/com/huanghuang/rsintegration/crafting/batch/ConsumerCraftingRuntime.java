package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.consumer.ConsumerAck;
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
import com.huanghuang.rsintegration.api.execution.FailureReason;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.TerminationCoordinator;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
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
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final int PLANNING_THREADS = 2;

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
                request.recipes().get(0).recipeId(),
                request.machineSelector(),
                request.storageSelector()
        );
        if (capture.failure != null) {
            return ConsumerResult.rejected(capture.failure);
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
            if (!session.capture.revisionCurrent()) {
                session.closed = true;
                return reject(
                        ConsumerDiagnosticCode.INSPECTION_SNAPSHOT_STALE,
                        "recipe revision changed during inspection",
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
                results = session.future.join();
            } catch (RuntimeException failure) {
                session.closed = true;
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
        ConsumerResult<ConsumerInspectionPage> invalid =
                validateSession(player, session, capabilities);
        if (invalid != null) {
            return session == null || !session.closed
                    ? rejectLike(invalid)
                    : ConsumerResult.success(new ConsumerAck(true));
        }
        session.closed = true;
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
            return replay(previous.result());
        }
        if (activeOrderCount(player.getUUID())
                >= capabilities.maxConcurrentOrdersPerPlayer()) {
            return reject(
                    ConsumerDiagnosticCode.ORDER_LIMIT_REACHED,
                    "player has too many active consumer orders",
                    true
            );
        }

        PlanLease plan = plans.get(request.planId());
        if (plan == null || !plan.playerId.equals(player.getUUID())
                || !plan.consumerId.equals(request.consumerId())) {
            return reject(
                    ConsumerDiagnosticCode.PLAN_NOT_FOUND,
                    "consumer plan was not found",
                    false
            );
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
            submissions.put(key, new SubmissionRecord(request.planId(), expired));
            return expired;
        }
        if (!CraftPlanningRevision.isCurrent(plan.revision)) {
            ConsumerResult<ConsumerOrderReceipt> stale = reject(
                    ConsumerDiagnosticCode.PLAN_STALE,
                    "recipe revision changed before submission",
                    true
            );
            submissions.put(key, new SubmissionRecord(request.planId(), stale));
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
            submissions.put(key, new SubmissionRecord(
                    request.planId(), rejectedStorage));
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
            submissions.put(key, new SubmissionRecord(request.planId(), stale));
            return stale;
        }

        List<CraftingResolver.ResolutionStep> steps;
        try {
            steps = PurePlanAdapter.toResolutionSteps(
                    plan.result, plan.planningGraph);
        } catch (RuntimeException failure) {
            ConsumerResult<ConsumerOrderReceipt> invalid = reject(
                    ConsumerDiagnosticCode.ORDER_SUBMISSION_REJECTED,
                    "stored consumer plan cannot be projected into execution",
                    false
            );
            submissions.put(key, new SubmissionRecord(request.planId(), invalid));
            return invalid;
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
        try {
            AsyncCraftChain chain = CraftExecutionService.launch(
                    player,
                    endpoint,
                    steps,
                    LegacyExecutionMetrics.Reason.PLANNED_CHAIN,
                    plan.recipeId,
                    plan.targetOutput,
                    outputDestination(plan.destination),
                    running -> {
                        order.chain = running;
                        running.setTargetOutput(plan.targetOutput);
                        running.setMachineSelection(
                                plan.recipeId,
                                plan.machineSelectionMode,
                                plan.dimension,
                                plan.position
                        );
                        running.onDone(() -> markOrderTerminal(order));
                    }
            );
            order.chain = chain;
        } catch (RuntimeException failure) {
            order.state = ConsumerOrderState.FAILED;
            order.failureReason = FailureReason.UNKNOWN;
        }
        ConsumerOrderReceipt receipt = new ConsumerOrderReceipt(
                orderId, order.currentState());
        ConsumerResult<ConsumerOrderReceipt> result =
                ConsumerResult.success(receipt);
        submissions.put(key, new SubmissionRecord(request.planId(), result));
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
            return replay(previous.result());
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
        batchSubmissions.put(key, new BatchSubmissionRecord(
                List.copyOf(request.orders()), result));
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
            cancellations.put(key, new CancelRecord(orderId, notCancellable));
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
        cancellations.put(key, new CancelRecord(orderId, result));
        return result;
    }

    public void clearPlayer(UUID playerId) {
        sessions.entrySet().removeIf(entry -> {
            if (!entry.getValue().ticket.playerId().equals(playerId)) {
                return false;
            }
            entry.getValue().closed = true;
            return true;
        });
        inspectionRequests.keySet().removeIf(key ->
                key.playerId().equals(playerId));
        plans.entrySet().removeIf(entry ->
                entry.getValue().playerId.equals(playerId));
        storageBindings.entrySet().removeIf(entry ->
                entry.getValue().playerId().equals(playerId));
    }

    private List<ConsumerCraftability> inspectPage(
            Session session,
            List<ConsumerRecipeKey> recipes,
            ConsumerCapabilities capabilities
    ) {
        List<ConsumerCraftability> results = new ArrayList<>(recipes.size());
        for (ConsumerRecipeKey recipe : recipes) {
            results.add(inspectOne(session, recipe, capabilities));
        }
        return List.copyOf(results);
    }

    private ConsumerCraftability inspectOne(
            Session session,
            ConsumerRecipeKey key,
            ConsumerCapabilities capabilities
    ) {
        RecipeNode indexed = session.capture.graph.recipesById().get(
                key.recipeId());
        if (indexed == null) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.RECIPE_NOT_FOUND,
                    ConsumerDiagnosticCode.RECIPE_NOT_FOUND,
                    "recipe is not projected into the current graph"
            );
        }
        if (key.amount() instanceof ConsumerRequestAmount.Executions executions
                && executions.count() > capabilities.maxRepeatCount()) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.PLAN_LIMIT_REACHED,
                    ConsumerDiagnosticCode.BATCH_LIMIT_EXCEEDED,
                    "requested execution count exceeds the server limit"
            );
        }
        MaterialResolution material;
        try {
            material = material(key, indexed);
        } catch (RuntimeException failure) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.RECIPE_UNSUPPORTED,
                    ConsumerDiagnosticCode.RECIPE_UNSUPPORTED,
                    "target output cannot be represented by the recursive planner"
            );
        }
        if (session.capture.blockedOutputIds.contains(
                material.material.itemId())) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.MACHINE_UNAVAILABLE,
                    ConsumerDiagnosticCode.MACHINE_UNAVAILABLE,
                    "no usable bound machine can execute the target recipe"
            );
        }

        Map<ResourceLocation, ResourceLocation> forced = forcedRecipes(
                key.preferences());
        forced.put(material.material.itemId(), key.recipeId());
        PlanningPreferences preferences = planningPreferences(
                key.preferences());
        ImmutableRecipeGraph scoped = ImmutableRecipeGraphProjector
                .restrictToDependencies(
                        session.capture.graph, key.recipeId());
        ImmutableRecipeGraph projected = ForcedRecipeGraphProjector.apply(
                scoped, forced);
        if (projected == null
                || !projected.recipesById().containsKey(key.recipeId())) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.RECIPE_UNSUPPORTED,
                    ConsumerDiagnosticCode.RECIPE_UNSUPPORTED,
                    "target recipe cannot be forced into the planning graph"
            );
        }
        if (projected.recipesById().size() > capabilities.maxPlanNodes()) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.PLAN_LIMIT_REACHED,
                    ConsumerDiagnosticCode.PLAN_LIMIT_REACHED,
                    "recursive plan exceeds the node limit"
            );
        }

        long requested = material.requestedCount;
        long deadline = deadlineAfterMillis(
                30_000L);
        PureRecipePlanner.Result result;
        try {
            long proposedStates = SaturatingLongMath.multiply(
                    requested, 32L);
            result = PureRecipePlanner.resolveConfigured(
                    projected,
                    ImmutableRecipeGraphProjector.projectAvailability(
                            session.capture.available),
                    List.of(new IngredientRef(
                            List.of(material.material),
                            requested,
                            NbtMatchMode.EXACT
                    )),
                    Math.max(1, SaturatingLongMath.clampToInt(
                            Math.max(32L, proposedStates))),
                    capabilities.maxSearchStates(),
                    8192,
                    deadline,
                    forced,
                    preferences
            );
        } catch (RuntimeException failure) {
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.INTERNAL_ERROR,
                    ConsumerDiagnosticCode.INTERNAL_ERROR,
                    "recursive planning failed"
            );
        }

        if (!result.feasible()) {
            if (!result.missing().isEmpty()) {
                return missing(key, requested, result);
            }
            return unavailable(
                    key,
                    ConsumerCraftabilityStatus.RECIPE_UNSUPPORTED,
                    ConsumerDiagnosticCode.RECIPE_UNSUPPORTED,
                    "recursive planner could not construct an executable plan"
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
                false,
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
                projected,
                result,
                key.recipeId(),
                material.targetOutput,
                session.capture.dimension,
                session.capture.position,
                machineSelectionMode(session.request.machineSelector()),
                session.request.outputDestination(),
                session.capture.storageReference
        );
        plans.put(planId, lease);
        long executable = key.amount()
                instanceof ConsumerRequestAmount.OutputUnits
                ? requested : 0L;
        return new ConsumerCraftability(
                key,
                ConsumerCraftabilityStatus.READY,
                requestedQuantity(key),
                executable,
                List.of(),
                planId,
                List.of()
        );
    }

    private MaterialResolution material(
            ConsumerRecipeKey key,
            RecipeNode recipe
    ) {
        ItemStack target;
        if (key.targetOutput() == null) {
            target = materialStack(recipe.output());
        } else {
            target = itemStack(key.targetOutput());
        }
        MaterialRef material = ImmutableRecipeGraphProjector.material(
                target, target.hasTag());
        long requested;
        if (key.amount()
                instanceof ConsumerRequestAmount.Executions executions) {
            requested = SaturatingLongMath.multiply(
                    recipe.outputCount(), executions.count());
        } else {
            requested = ((ConsumerRequestAmount.OutputUnits) key.amount())
                    .quantity();
        }
        return new MaterialResolution(target, material, requested);
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

    private static ItemStack materialStack(MaterialRef material) {
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
        return IntegrationResourceKey.item(materialStack(material));
    }

    private static long requestedQuantity(ConsumerRecipeKey key) {
        return key.amount() instanceof ConsumerRequestAmount.OutputUnits units
                ? units.quantity() : 0L;
    }

    private CaptureResult capture(
            ServerPlayer player,
            ResourceLocation terminalRecipeId,
            ConsumerMachineSelector machineSelector,
            ConsumerStorageSelector storageSelector
    ) {
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
        GenericCraftPacket.BindingAwareGraph bindingGraph =
                GenericCraftPacket.bindingAwareGraph(
                        player,
                        ImmutableRecipeGraphProjector.capture(
                                player.serverLevel())
                );
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
                        bindingGraph.graph(),
                        bindingGraph.blockedOutputIds(),
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
        if (order.state == ConsumerOrderState.FAILED) {
            order.failureReason = failureReason(snapshot.reason());
        }
    }

    private static FailureReason failureReason(
            CraftProgressSnapshot.Reason reason
    ) {
        return switch (reason) {
            case MACHINE_BUSY, NO_BOUND_MACHINE -> FailureReason.MACHINE_INVALID;
            case OUTPUT_MISSING -> FailureReason.OUTPUT_INVALID;
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
            session.closed = true;
            return reject(
                    ConsumerDiagnosticCode.SESSION_EXPIRED,
                    "inspection session has expired",
                    true
            );
        }
        return null;
    }

    private static <T> ConsumerResult<T> rejectLike(
            ConsumerResult<?> source
    ) {
        return ConsumerResult.rejected(
                source.diagnostics(), source.idempotentReplay());
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

    private static long deadlineAfterMillis(long millis) {
        long now = System.nanoTime();
        long budget = Math.max(1L, millis) * 1_000_000L;
        long deadline = now + budget;
        return deadline < now ? Long.MAX_VALUE : deadline;
    }

    private void pruneSessions(int maximum) {
        if (sessions.size() <= maximum) return;
        sessions.values().stream()
                .filter(session -> session.closed)
                .limit(Math.max(0, sessions.size() - maximum))
                .forEach(session -> sessions.remove(
                        session.ticket.ticketId(), session));
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
            ItemStack targetOutput,
            MaterialRef material,
            long requestedCount
    ) {
        private MaterialResolution {
            targetOutput = targetOutput.copy();
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
            ImmutableRecipeGraph graph,
            Set<ResourceLocation> blockedOutputIds,
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
            blockedOutputIds = Set.copyOf(blockedOutputIds);
        }

        private boolean revisionCurrent() {
            return CraftPlanningRevision.isCurrent(revision);
        }
    }

    private static final class Session {
        private final ConsumerInspectionTicket ticket;
        private final ConsumerInspectionRequest request;
        private final Capture capture;
        private final long createdGameTime;
        private boolean closed;
        private int nextOffset;
        private int futureOffset;
        private int futureEnd;
        private CompletableFuture<List<ConsumerCraftability>> future;

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
        private final PureRecipePlanner.Result result;
        private final ResourceLocation recipeId;
        private final ItemStack targetOutput;
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
                PureRecipePlanner.Result result,
                ResourceLocation recipeId,
                ItemStack targetOutput,
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
            this.result = result;
            this.recipeId = recipeId;
            this.targetOutput = targetOutput.copy();
            this.dimension = dimension;
            this.position = position.immutable();
            this.machineSelectionMode = machineSelectionMode;
            this.destination = destination;
            this.storageReference = storageReference;
        }
    }

    private static final class OrderRecord {
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

        private OrderRecord(
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

        private ConsumerOrderStatus status() {
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
                    List.<ConsumerOutputResult>of(),
                    failure,
                    current == ConsumerOrderState.FAILED
                            ? List.of(publicDiagnostic(
                            IntegrationDiagnosticCode.INTEGRATION_REPORTED_FAILURE,
                            "consumer order failed"
                    ))
                            : List.of()
            );
        }
    }

    private record InspectionRequestKey(
            UUID playerId,
            ResourceLocation consumerId,
            UUID requestId
    ) {}

    private record SubmitKey(
            UUID playerId,
            ResourceLocation consumerId,
            UUID requestId
    ) {}

    private record BatchSubmitKey(
            UUID playerId,
            ResourceLocation consumerId,
            UUID requestId
    ) {}

    private record CancelKey(
            UUID playerId,
            ResourceLocation consumerId,
            UUID requestId
    ) {}

    private record SubmissionRecord(
            ConsumerPlanId planId,
            ConsumerResult<ConsumerOrderReceipt> result
    ) {}

    private record BatchSubmissionRecord(
            List<ConsumerOrderRequest> orders,
            ConsumerResult<ConsumerBatchOrderReceipt> result
    ) {}

    private record CancelRecord(
            ConsumerOrderId orderId,
            ConsumerResult<ConsumerCancelResult> result
    ) {}

    private record StorageBinding(
            UUID playerId,
            @Nullable StorageReference reference
    ) {}
}
