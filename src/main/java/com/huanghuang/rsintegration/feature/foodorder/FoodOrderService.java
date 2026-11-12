package com.huanghuang.rsintegration.feature.foodorder;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.consumer.ConsumerCraftability;
import com.huanghuang.rsintegration.api.consumer.ConsumerDiagnostic;
import com.huanghuang.rsintegration.api.consumer.ConsumerDiagnosticCode;
import com.huanghuang.rsintegration.api.consumer.ConsumerExternalRequestId;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionOptions;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionPage;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionPageState;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionRequestId;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionTicket;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionTicketId;
import com.huanghuang.rsintegration.api.consumer.ConsumerMachineSelector;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderId;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderReceipt;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerOutputDestination;
import com.huanghuang.rsintegration.api.consumer.ConsumerPlanId;
import com.huanghuang.rsintegration.api.consumer.ConsumerPlanningPreferences;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeKey;
import com.huanghuang.rsintegration.api.consumer.ConsumerRequestAmount;
import com.huanghuang.rsintegration.api.consumer.ConsumerResult;
import com.huanghuang.rsintegration.api.consumer.ConsumerServiceState;
import com.huanghuang.rsintegration.api.consumer.ConsumerStorageOption;
import com.huanghuang.rsintegration.api.consumer.ConsumerStorageSelector;
import com.huanghuang.rsintegration.api.consumer.RSICraftConsumerApi;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.feature.foodorder.network.FoodOrderResultPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 服务端下单状态机。
 *
 * <p>全部下单保留完整候选队列，分批预检并逐单提交；前一批执行结束后，再以新库存
 * 继续下一批。批次大小与并发限制只影响推进速度，不截断食物总数。
 * 所有 Consumer API 调用都在服务端主线程，每个玩家同一时间只允许一个点餐任务。</p>
 */
public final class FoodOrderService {

    private static final ResourceLocation CONSUMER_ID =
            new ResourceLocation(RSIntegrationMod.MOD_ID, "food_order");

    private static final Map<UUID, Job> JOBS = new HashMap<>();
    private static final Map<UUID, Long> LAST_ORDER = new HashMap<>();

    private FoodOrderService() {}

    public static boolean holding(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return player.getMainHandItem().is(ModItems.ORDER_TERMINAL.get())
                || player.getOffhandItem().is(ModItems.ORDER_TERMINAL.get());
    }

    public static void submit(ServerPlayer player, ItemStack target, boolean all,
                              @Nullable ResourceLocation recipeId, long epoch) {
        try {
            submitInternal(player, target, all, recipeId, epoch);
        } catch (Throwable throwable) {
            // 网络线程只负责转交；这里兜底，保证异常时客户端不会一直停在“准备中”。
            RSIntegrationMod.LOGGER.error("[RSI-FoodOrder] 下单请求异常", throwable);
            sendFailure(player, "rsi.food_order.error.failed", "");
        }
    }

    private static void submitInternal(ServerPlayer player, ItemStack target, boolean all,
                                       @Nullable ResourceLocation recipeId, long epoch) {
        if (!RSIntegrationConfig.ENABLE_FOOD_ORDER.get()) {
            sendFailure(player, "rsi.food_order.error.disabled", "");
            return;
        }
        if (!holding(player) || player.hasDisconnected()) {
            return;
        }
        if (JOBS.containsKey(player.getUUID())) {
            sendFailure(player, "rsi.food_order.error.busy", "");
            return;
        }
        long now = player.serverLevel().getGameTime();
        Long last = LAST_ORDER.get(player.getUUID());
        int cooldown = Math.max(0, RSIntegrationConfig.FOOD_ORDER_ORDER_COOLDOWN_TICKS.get());
        if (last != null && now - last < cooldown) {
            return;
        }
        LAST_ORDER.put(player.getUUID(), now);

        Optional<RSICraftConsumerApi> maybeApi = RSICraftConsumerApi.get();
        if (maybeApi.isEmpty()) {
            sendFailure(player, "rsi.food_order.error.unavailable", "");
            return;
        }
        RSICraftConsumerApi api = maybeApi.get();
        if (api.state() == ConsumerServiceState.INDEXING) {
            sendFailure(player, "rsi.food_order.error.indexing", "");
            return;
        }
        if (api.state() != ConsumerServiceState.READY) {
            sendFailure(player, "rsi.food_order.error.service", api.state().name());
            return;
        }

        FoodOrderSnapshot snapshot = FoodOrderListService.cached(player);
        if (snapshot == null) {
            snapshot = FoodOrderListService.snapshot(player, true);
        }
        if (snapshot.indexing()) {
            sendFailure(player, "rsi.food_order.error.indexing", "");
            return;
        }
        List<FoodOrderEntry> selected = selectEntries(snapshot, target, all, recipeId);
        if (selected.isEmpty()) {
            // 列表尚在分批计算时，没有候选不代表没有可下单的食物。
            sendFailure(player, snapshot.partial() ? "rsi.food_order.loading"
                    : all ? "rsi.food_order.error.nothing" : "rsi.food_order.error.notarget", "");
            return;
        }

        List<ConsumerRecipeKey> keys = new ArrayList<>(selected.size());
        var seenRecipes = new HashSet<ResourceLocation>();
        for (FoodOrderEntry entry : selected) {
            if (entry.recipeId() == null || !seenRecipes.add(entry.recipeId())) {
                // 同一条配方可能对应多个展示条目；Consumer 检查会话要求 recipeId 唯一。
                continue;
            }
            keys.add(new ConsumerRecipeKey(entry.recipeId(), null,
                    new ConsumerRequestAmount.Executions(1),
                    ConsumerPlanningPreferences.empty()));
        }
        if (keys.isEmpty()) {
            sendFailure(player, "rsi.food_order.error.notarget", "");
            return;
        }

        Job job = new Job(player.getUUID(), keys, now);
        JOBS.put(player.getUUID(), job);
        // 真正的 openInspection 放到服务端 tick 里串行执行，避免玩家点击瞬间同步 capture。
        send(player, FoodOrderResultPacket.queued(keys.size()));
        RSIntegrationMod.LOGGER.debug(
                "[RSI-FoodOrder] 玩家 {} 开始下单：{} 道菜（epoch={}）",
                player.getGameProfile().getName(), keys.size(), epoch);
    }

    public static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        int interval = Math.max(1, RSIntegrationConfig.FOOD_ORDER_ORDER_INTERVAL_TICKS.get());
        int timeout = Math.max(20, RSIntegrationConfig.FOOD_ORDER_SESSION_TIMEOUT_TICKS.get());
        boolean stepped = false;
        for (Job job : new ArrayList<>(JOBS.values())) {
            if (JOBS.get(job.playerId) != job) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(job.playerId);
            if (player == null || player.hasDisconnected()) {
                JOBS.remove(job.playerId);
                continue;
            }
            if (now - job.lastProgressAt > timeout + (long) job.plans.size() * interval) {
                // 超时也要关闭检查会话，避免 plan 占着容量直到 TTL 过期。
                finish(job, player, RSICraftConsumerApi.get().orElse(null));
                sendFailure(player, "rsi.food_order.error.timeout", "");
                continue;
            }
            Optional<RSICraftConsumerApi> maybeApi = RSICraftConsumerApi.get();
            if (maybeApi.isEmpty()) {
                finish(job, player, null);
                sendFailure(player, "rsi.food_order.error.unavailable", "");
                continue;
            }
            if (job.stage == Stage.START) {
                // 排队的下单优先启动，且不受 orderIntervalTicks 限制。
                if (stepped) {
                    continue;
                }
                stepped = true;
                job.lastStep = now;
                try {
                    start(player, job, maybeApi.get());
                } catch (Throwable throwable) {
                    RSIntegrationMod.LOGGER.warn(
                            "[RSI-FoodOrder] 创建检查会话异常，已取消本次任务", throwable);
                    finish(job, player, maybeApi.get());
                    sendFailure(player, "rsi.food_order.error.failed", "INTERNAL_ERROR");
                }
                continue;
            }
            if (stepped || now - job.lastStep < interval) {
                continue;
            }
            stepped = true;
            job.lastStep = now;
            try {
                step(player, job, maybeApi.get());
            } catch (Throwable throwable) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FoodOrder] 下单步骤异常，已取消本次任务", throwable);
                finish(job, player, maybeApi.get());
                sendFailure(player, "rsi.food_order.error.failed", "INTERNAL_ERROR");
            }
        }
    }

    public static void clearPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }
        Job job = JOBS.remove(player.getUUID());
        LAST_ORDER.remove(player.getUUID());
        if (job != null) {
            finish(job, player, RSICraftConsumerApi.get().orElse(null));
        }
    }

    public static void clearPlayer(UUID playerId) {
        Job job = JOBS.remove(playerId);
        LAST_ORDER.remove(playerId);
        if (job != null) {
            job.plans.clear();
        }
    }

    public static void clear() {
        JOBS.clear();
        LAST_ORDER.clear();
    }

    private static void start(ServerPlayer player, Job job, RSICraftConsumerApi api) {
        var limits = api.capabilities();
        int batchSize = Math.max(1, Math.min(
                RSIntegrationConfig.FOOD_ORDER_MAX_ORDERS_PER_BATCH.get(),
                Math.min(limits.maxOrdersPerBatch(), Math.min(limits.maxRecipesPerSession(),
                        limits.maxConcurrentOrdersPerPlayer()))));
        List<ConsumerRecipeKey> batch = new ArrayList<>(batchSize);
        while (!job.pending.isEmpty() && batch.size() < batchSize) {
            batch.add(job.pending.removeFirst());
        }
        int pageSize = Math.min(batch.size(),
                Math.max(1, Math.min(api.capabilities().maxPageSize(), 64)));
        ConsumerInspectionRequest request = new ConsumerInspectionRequest(
                CONSUMER_ID,
                new ConsumerInspectionRequestId(UUID.randomUUID()),
                batch,
                new ConsumerMachineSelector.AutomaticBound(),
                new ConsumerStorageSelector.Default(),
                outputDestination(player, api),
                new ConsumerInspectionOptions(pageSize, false));
        ConsumerResult<ConsumerInspectionTicket> opened = api.openInspection(player, request);
        if (!opened.successful() || opened.value() == null) {
            finish(job, player, null);
            failWithDiagnostics(player, opened.diagnostics(), "rsi.food_order.error.failed");
            return;
        }
        job.ticket = opened.value().ticketId();
        job.stage = Stage.POLL;
        if (!job.capacityWait) {
            job.lastProgressAt = player.serverLevel().getGameTime();
        }
        if (!job.started) {
            job.started = true;
            send(player, FoodOrderResultPacket.started(job.keys.size()));
        }
    }

    private static void step(ServerPlayer player, Job job, RSICraftConsumerApi api) {
        if (job.stage == Stage.WAIT_ORDERS) {
            if (!job.activeOrders.isEmpty()) {
                ConsumerOrderId orderId = job.activeOrders.removeFirst();
                var status = api.status(player, CONSUMER_ID, orderId);
                if (!status.successful() || status.value() == null || !status.value().terminal()) {
                    job.activeOrders.addLast(orderId);
                }
                if (status.successful()) {
                    // 正常运行的机器可能耗时很长；仍能查询到订单就继续等待，不用总队列时长截断。
                    job.lastProgressAt = player.serverLevel().getGameTime();
                }
            }
            if (job.activeOrders.isEmpty()
                    && player.serverLevel().getGameTime() >= job.retryAt) {
                job.stage = Stage.START;
            }
            return;
        }
        if (job.stage == Stage.POLL) {
            ConsumerResult<ConsumerInspectionPage> pageResult =
                    api.nextInspectionPage(player, job.ticket);
            if (!pageResult.successful() || pageResult.value() == null) {
                failJob(player, job, pageResult.diagnostics());
                return;
            }
            ConsumerInspectionPage page = pageResult.value();
            if (page.state() == ConsumerInspectionPageState.COMPLETE) {
                for (ConsumerCraftability craftability : page.results()) {
                    if (craftability.canSubmit() && craftability.planId() != null) {
                        job.plans.addLast(new PlannedOrder(craftability.recipe(), craftability.planId()));
                    } else {
                        job.skipped++;
                        job.lastProgressAt = player.serverLevel().getGameTime();
                    }
                }
            }
            if (page.complete()) {
                closeInspection(job, player, api);
                job.stage = Stage.SUBMIT;
            }
            if (page.state() == ConsumerInspectionPageState.COMPLETE && !job.capacityWait) {
                job.lastProgressAt = player.serverLevel().getGameTime();
            }
            return;
        }

        if (job.plans.isEmpty()) {
            nextBatchOrFinish(job, player, api);
            return;
        }

        // 逐单提交：每 orderIntervalTicks 只提交一个 plan，避免整批在同一 tick 同时开链。
        PlannedOrder planned = job.plans.getFirst();
        ConsumerPlanId planId = planned.planId();
        ConsumerOrderRequest order = new ConsumerOrderRequest(CONSUMER_ID,
                new ConsumerExternalRequestId(UUID.randomUUID()), planId);
        ConsumerResult<ConsumerOrderReceipt> submitted = api.submit(player, order);
        if (!submitted.successful() && submitted.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code() == ConsumerDiagnosticCode.ORDER_LIMIT_REACHED)) {
            // 并发满了不算失败，也不丢弃余下食物。释放旧计划，等有名额后用新库存重新预检。
            var iterator = job.plans.descendingIterator();
            while (iterator.hasNext()) {
                PlannedOrder remaining = iterator.next();
                job.pending.addFirst(remaining.recipe());
                api.discardPlan(player, CONSUMER_ID, remaining.planId());
            }
            job.plans.clear();
            job.capacityWait = true;
            job.retryAt = player.serverLevel().getGameTime() + 20L;
            waitForOrders(job, player);
            return;
        }
        job.plans.removeFirst();
        job.capacityWait = false;
        job.lastProgressAt = player.serverLevel().getGameTime();
        job.submitted++;
        if (submitted.successful()) {
            job.success++;
            job.activeOrders.addLast(submitted.value().orderId());
        } else {
            api.discardPlan(player, CONSUMER_ID, planId);
            job.skipped++;
        }
        RSIntegrationMod.LOGGER.debug("[RSI-FoodOrder] 提交第 {}/{} 单：{}",
                job.submitted, job.keys.size(), planId);
        if (!job.plans.isEmpty()) {
            return;
        }

        nextBatchOrFinish(job, player, api);
    }

    private static void nextBatchOrFinish(Job job, ServerPlayer player, RSICraftConsumerApi api) {
        if (!job.pending.isEmpty()) {
            waitForOrders(job, player);
            return;
        }
        finish(job, player, api);
        int success = job.success;
        int skipped = job.skipped;
        if (success <= 0) {
            sendFailure(player, "rsi.food_order.error.failed", "");
        } else if (skipped > 0) {
            send(player, FoodOrderResultPacket.partial(success, skipped));
        } else {
            send(player, FoodOrderResultPacket.success(success));
        }
    }

    private static void waitForOrders(Job job, ServerPlayer player) {
        job.stage = Stage.WAIT_ORDERS;
        send(player, FoodOrderResultPacket.waiting(job.pending.size()));
    }

    private static List<FoodOrderEntry> selectEntries(FoodOrderSnapshot snapshot,
                                                      ItemStack target,
                                                      boolean all,
                                                      @Nullable ResourceLocation recipeId) {
        if (all) {
            List<FoodOrderEntry> result = new ArrayList<>();
            for (FoodOrderEntry entry : snapshot.entries()) {
                if (entry.orderable()) {
                    result.add(entry);
                }
            }
            return result;
        }
        if (target.isEmpty()) {
            return List.of();
        }
        FoodOrderEntry fallback = null;
        for (FoodOrderEntry entry : snapshot.entries()) {
            if (!entry.orderable()) {
                continue;
            }
            if (entry.stack().getItem() != target.getItem()) {
                continue;
            }
            // 同一食物可能有多条配方，优先按客户端点中的那一条下单。
            if (recipeId != null && recipeId.equals(entry.recipeId())) {
                return List.of(entry);
            }
            if (fallback == null) {
                fallback = entry;
            }
        }
        return fallback == null ? List.of() : List.of(fallback);
    }

    private static ConsumerOutputDestination outputDestination(ServerPlayer player,
                                                               RSICraftConsumerApi api) {
        try {
            ConsumerResult<List<ConsumerStorageOption>> options = api.storageOptions(player);
            if (options.successful() && options.value() != null
                    && options.value().stream().anyMatch(option -> !option.playerInventoryIncluded())) {
                return ConsumerOutputDestination.RS_NETWORK;
            }
        } catch (Throwable throwable) {
            RSIntegrationMod.LOGGER.debug("[RSI-FoodOrder] 读取存储选项失败，回退到玩家背包输出", throwable);
        }
        return ConsumerOutputDestination.PLAYER_INVENTORY;
    }

    private static void failJob(ServerPlayer player, Job job,
                                List<ConsumerDiagnostic> diagnostics) {
        finish(job, player, RSICraftConsumerApi.get().orElse(null));
        failWithDiagnostics(player, diagnostics, "rsi.food_order.error.failed");
    }

    private static void failWithDiagnostics(ServerPlayer player,
                                            List<ConsumerDiagnostic> diagnostics,
                                            String fallbackKey) {
        ConsumerDiagnosticCode code = diagnostics == null || diagnostics.isEmpty()
                ? null : diagnostics.get(0).code();
        String key = fallbackKey;
        if (code == ConsumerDiagnosticCode.CATALOG_NOT_READY) {
            key = "rsi.food_order.error.indexing";
        } else if (code == ConsumerDiagnosticCode.STORAGE_UNAVAILABLE) {
            key = "rsi.food_order.error.storage";
        } else if (code == ConsumerDiagnosticCode.PLAN_LIMIT_REACHED) {
            key = "rsi.food_order.error.plan_limit";
        } else if (code == ConsumerDiagnosticCode.OUTPUT_UNAVAILABLE) {
            key = "rsi.food_order.error.output";
        }
        sendFailure(player, key, code == null ? "" : code.name());
    }

    private static void finish(Job job, ServerPlayer player,
                               @Nullable RSICraftConsumerApi api) {
        JOBS.remove(job.playerId);
        if (api != null && !player.hasDisconnected()) {
            for (PlannedOrder planned : job.plans) {
                try {
                    api.discardPlan(player, CONSUMER_ID, planned.planId());
                } catch (RuntimeException | LinkageError failure) {
                    RSIntegrationMod.LOGGER.debug("[RSI-FoodOrder] 释放未提交的计划失败，等待过期清理", failure);
                }
            }
        }
        job.plans.clear();
        closeInspection(job, player, api);
    }

    private static void closeInspection(Job job, ServerPlayer player,
                                         @Nullable RSICraftConsumerApi api) {
        if (api != null && job.ticket != null && !player.hasDisconnected()) {
            try {
                api.closeInspection(player, job.ticket);
            } catch (Throwable throwable) {
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-FoodOrder] 关闭检查会话失败，忽略", throwable);
            }
        }
        job.ticket = null;
    }

    private static void sendFailure(ServerPlayer player, String key, String detail) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        send(player, FoodOrderResultPacket.failed(key, detail));
    }

    private static void send(ServerPlayer player, FoodOrderResultPacket packet) {
        FoodOrderResultPacket.send(player, packet);
    }

    private enum Stage {
        START,
        POLL,
        SUBMIT,
        WAIT_ORDERS
    }

    private record PlannedOrder(ConsumerRecipeKey recipe, ConsumerPlanId planId) {}

    private static final class Job {
        private final UUID playerId;
        private final List<ConsumerRecipeKey> keys;
        private final Deque<ConsumerRecipeKey> pending;
        @Nullable
        private ConsumerInspectionTicketId ticket;
        private long lastStep;
        private long lastProgressAt;
        private long retryAt;
        private boolean capacityWait;
        private boolean started;
        private Stage stage = Stage.START;
        private final Deque<PlannedOrder> plans = new ArrayDeque<>();
        private final Deque<ConsumerOrderId> activeOrders = new ArrayDeque<>();
        private int submitted;
        private int success;
        private int skipped;

        private Job(UUID playerId, List<ConsumerRecipeKey> keys, long startedAt) {
            this.playerId = playerId;
            this.keys = List.copyOf(keys);
            this.pending = new ArrayDeque<>(keys);
            this.lastProgressAt = startedAt;
            this.lastStep = startedAt;
        }
    }
}
