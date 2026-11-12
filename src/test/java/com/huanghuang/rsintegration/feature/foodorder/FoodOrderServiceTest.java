package com.huanghuang.rsintegration.feature.foodorder;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.consumer.ConsumerAck;
import com.huanghuang.rsintegration.api.consumer.ConsumerCapabilities;
import com.huanghuang.rsintegration.api.consumer.ConsumerCraftability;
import com.huanghuang.rsintegration.api.consumer.ConsumerCraftabilityStatus;
import com.huanghuang.rsintegration.api.consumer.ConsumerDiagnostic;
import com.huanghuang.rsintegration.api.consumer.ConsumerDiagnosticCode;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionPage;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionPageState;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionTicket;
import com.huanghuang.rsintegration.api.consumer.ConsumerInspectionTicketId;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderId;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderReceipt;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderRequest;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderState;
import com.huanghuang.rsintegration.api.consumer.ConsumerOrderStatus;
import com.huanghuang.rsintegration.api.consumer.ConsumerPlanId;
import com.huanghuang.rsintegration.api.consumer.ConsumerRecipeKey;
import com.huanghuang.rsintegration.api.consumer.ConsumerResult;
import com.huanghuang.rsintegration.api.consumer.ConsumerServiceState;
import com.huanghuang.rsintegration.api.consumer.RSICraftConsumerApi;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.feature.foodorder.network.FoodOrderResultPacket;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 使用受限 Consumer API 驱动真实点餐状态机，验证完整队列而非只验证分批函数。 */
class FoodOrderServiceTest extends BootstrapTest {
    private final AtomicLong time = new AtomicLong(1);
    private final List<FoodOrderResultPacket> messages = new ArrayList<>();
    private final List<ConsumerInspectionRequest> inspections = new ArrayList<>();
    private final Map<ConsumerInspectionTicketId, ConsumerInspectionRequest> sessions = new HashMap<>();
    private final Map<ConsumerInspectionTicketId, Integer> offsets = new HashMap<>();
    private final Map<ConsumerPlanId, ConsumerRecipeKey> plans = new HashMap<>();
    private final Map<ConsumerPlanId, Long> plannedAt = new HashMap<>();
    private final Map<ConsumerOrderId, Long> orders = new HashMap<>();
    private final Set<ResourceLocation> submittedRecipes = new HashSet<>();
    private final Set<ResourceLocation> unavailable = new HashSet<>();
    private final Set<ResourceLocation> rejected = new HashSet<>();
    private final Set<UUID> requestIds = new HashSet<>();
    private MockedStatic<FoodOrderService> service;
    private MockedStatic<FoodOrderListService> lists;
    private MockedStatic<RSICraftConsumerApi> lookup;
    private MockedStatic<FoodOrderResultPacket> packets;
    private RSICraftConsumerApi api;
    private MinecraftServer server;
    private ServerPlayer player;
    private ConsumerCapabilities limits = limits(64, 4096, 32);
    private int orderDuration = 10;
    private int capacityRejectionAt = -1;
    private boolean capacityRejected;
    private boolean alwaysAtCapacity;
    private boolean inspectionPending;
    private int peakActive;
    private int submitCalls;
    private int discarded;
    private int closed;

    @BeforeAll
    static void initializeLogger() {
        var context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            assertNotNull(RSIntegrationMod.LOGGER);
        }
    }

    @BeforeEach
    void setUp() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.COMMON_SPEC.correct(config);
        RSIntegrationConfig.COMMON_SPEC.setConfig(config);
        server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        PlayerList players = mock(PlayerList.class);
        player = mock(ServerPlayer.class);
        UUID playerId = UUID.randomUUID();
        when(server.overworld()).thenReturn(level);
        when(server.getPlayerList()).thenReturn(players);
        when(level.getGameTime()).thenAnswer(ignored -> time.get());
        when(player.serverLevel()).thenReturn(level);
        when(player.getUUID()).thenReturn(playerId);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerId, "food_order_test"));
        when(players.getPlayer(playerId)).thenReturn(player);
        // 只替换手持终端鉴权与网络发送；下单、分批和 tick 推进均运行正式逻辑。
        service = mockStatic(FoodOrderService.class, call ->
                call.getMethod().getName().equals("holding") ? true : call.callRealMethod());
        packets = mockStatic(FoodOrderResultPacket.class, call -> {
            if (call.getMethod().getName().equals("send")) {
                messages.add(call.getArgument(1));
                return null;
            }
            return call.callRealMethod();
        });
        lists = mockStatic(FoodOrderListService.class);
        lookup = mockStatic(RSICraftConsumerApi.class);
        api = mock(RSICraftConsumerApi.class);
        lookup.when(RSICraftConsumerApi::get).thenReturn(Optional.of(api));
        when(api.state()).thenReturn(ConsumerServiceState.READY);
        when(api.capabilities()).thenAnswer(ignored -> limits);
        when(api.storageOptions(player)).thenReturn(ConsumerResult.success(List.of()));
        when(api.openInspection(eq(player), any())).thenAnswer(call -> {
            ConsumerInspectionRequest request = call.getArgument(1);
            assertTrue(request.recipes().size() <= limits.maxRecipesPerSession());
            assertTrue(request.recipes().size() <= limits.maxConcurrentOrdersPerPlayer());
            assertTrue(request.options().pageSize() <= limits.maxPageSize());
            ConsumerInspectionTicketId ticket = new ConsumerInspectionTicketId(UUID.randomUUID());
            inspections.add(request);
            sessions.put(ticket, request);
            return ConsumerResult.success(new ConsumerInspectionTicket(ticket, playerId,
                    request.consumerId(), request.recipes().size(), request.options().pageSize(), 1));
        });
        when(api.nextInspectionPage(eq(player), any())).thenAnswer(call -> {
            ConsumerInspectionTicketId ticket = call.getArgument(1);
            ConsumerInspectionRequest request = sessions.get(ticket);
            int offset = offsets.getOrDefault(ticket, 0);
            if (inspectionPending) {
                return ConsumerResult.success(new ConsumerInspectionPage(ticket, offset,
                        ConsumerInspectionPageState.RUNNING, null, 1, List.of()));
            }
            int end = Math.min(request.recipes().size(), offset + request.options().pageSize());
            List<ConsumerCraftability> results = new ArrayList<>();
            for (ConsumerRecipeKey key : request.recipes().subList(offset, end)) {
                if (unavailable.contains(key.recipeId())) {
                    results.add(new ConsumerCraftability(key, ConsumerCraftabilityStatus.RECIPE_UNSUPPORTED,
                            1, 0, List.of(), null, List.of(IntegrationDiagnostic.error(
                                    IntegrationDiagnosticCode.UNKNOWN))));
                    continue;
                }
                ConsumerPlanId plan = new ConsumerPlanId(UUID.randomUUID());
                plans.put(plan, key);
                plannedAt.put(plan, time.get());
                results.add(new ConsumerCraftability(key, ConsumerCraftabilityStatus.READY,
                        1, 1, List.of(), plan, List.of()));
            }
            offsets.put(ticket, end);
            return ConsumerResult.success(new ConsumerInspectionPage(ticket, offset,
                    ConsumerInspectionPageState.COMPLETE,
                    end < request.recipes().size() ? end : null, 1, results));
        });
        when(api.submit(eq(player), any())).thenAnswer(call -> {
            submitCalls++;
            ConsumerOrderRequest request = call.getArgument(1);
            assertTrue(requestIds.add(request.externalRequestId().value()), "重试新计划需使用新幂等键");
            if (alwaysAtCapacity || (!capacityRejected && submittedRecipes.size() == capacityRejectionAt)) {
                capacityRejected = true;
                return failure(ConsumerDiagnosticCode.ORDER_LIMIT_REACHED);
            }
            ConsumerRecipeKey key = plans.remove(request.planId());
            assertNotNull(key, "不能重复提交已消费或已释放的计划");
            assertTrue(time.get() - plannedAt.get(request.planId()) <= limits.planTtlTicks(),
                    "不能在等待机器结束后继续提交过期计划");
            if (rejected.contains(key.recipeId())) {
                return failure(ConsumerDiagnosticCode.PLAN_STALE);
            }
            int active = (int) orders.values().stream().filter(until -> until > time.get()).count();
            assertTrue(active < limits.maxConcurrentOrdersPerPlayer(), "不能突破底层并发上限");
            peakActive = Math.max(peakActive, active + 1);
            assertTrue(submittedRecipes.add(key.recipeId()), "同一道菜只能被接受一次");
            ConsumerOrderId order = new ConsumerOrderId(UUID.randomUUID());
            orders.put(order, time.get() + orderDuration);
            return ConsumerResult.success(new ConsumerOrderReceipt(order, ConsumerOrderState.RUNNING));
        });
        when(api.status(eq(player), any(), any())).thenAnswer(call -> {
            ConsumerOrderId order = call.getArgument(2);
            boolean complete = orders.get(order) <= time.get();
            return ConsumerResult.success(new ConsumerOrderStatus(order, call.getArgument(1), recipe(0),
                    complete ? ConsumerOrderState.SUCCEEDED : ConsumerOrderState.RUNNING,
                    complete ? 1 : 0, 1, complete ? 0 : 1, List.of(), null, List.of()));
        });
        when(api.discardPlan(eq(player), any(), any())).thenAnswer(call -> {
            plans.remove(call.getArgument(2));
            discarded++;
            return ConsumerResult.success(new ConsumerAck(true));
        });
        when(api.closeInspection(eq(player), any())).thenAnswer(call -> {
            sessions.remove(call.getArgument(1));
            closed++;
            return ConsumerResult.success(new ConsumerAck(true));
        });
        FoodOrderService.clear();
    }

    @AfterEach
    void tearDown() {
        FoodOrderService.clearPlayer(player);
        FoodOrderService.clear();
        lookup.close();
        lists.close();
        packets.close();
        service.close();
        RSIntegrationConfig.COMMON_SPEC.setConfig(null);
    }

    @Test
    void allThreeHundredFoodsAreQueuedAndSubmittedAcrossDefaultBatches() {
        orderDuration = 200;
        submitAll(300);
        assertEquals(300, messages.get(0).accepted());
        runUntilFinished(10_000);

        assertEquals(300, submittedRecipes.size());
        assertEquals(10, inspections.size());
        assertTrue(inspections.stream().allMatch(request -> request.recipes().size() <= 32));
        assertTrue(peakActive <= 32);
        assertEquals(300, lastMessage().accepted());
        assertEquals(FoodOrderResultPacket.SUCCESS, lastMessage().code());
        assertEquals(inspections.size(), closed);
        assertTrue(plans.isEmpty());
        assertTrue(sessions.isEmpty());
    }

    @Test
    void smallerApiSessionAndPageLimitsStillProcessTheWholeQueue() {
        RSIntegrationConfig.FOOD_ORDER_MAX_ORDERS_PER_BATCH.set(64);
        limits = limits(3, 7, 32);
        submitAll(77);
        runUntilFinished(2000);

        assertEquals(77, submittedRecipes.size());
        assertEquals(11, inspections.size());
        assertTrue(inspections.stream().allMatch(request -> request.recipes().size() == 7));
        assertEquals(FoodOrderResultPacket.SUCCESS, lastMessage().code());
    }

    @Test
    void slowMachinesDoNotExpireTheQueueAndLaterBatchesUseFreshPlans() {
        RSIntegrationConfig.FOOD_ORDER_MAX_ORDERS_PER_BATCH.set(2);
        RSIntegrationConfig.FOOD_ORDER_SESSION_TIMEOUT_TICKS.set(20);
        orderDuration = 1000;
        submitAll(5);
        runUntilFinished(4000);

        assertEquals(5, submittedRecipes.size());
        assertEquals(3, inspections.size());
        assertEquals(FoodOrderResultPacket.SUCCESS, lastMessage().code());
    }

    @Test
    void fullConcurrencyRequeuesUnsubmittedFoodsWithoutCountingThemAsSkipped() {
        RSIntegrationConfig.FOOD_ORDER_MAX_ORDERS_PER_BATCH.set(4);
        capacityRejectionAt = 3;
        orderDuration = 800;
        submitAll(9);
        runUntilFinished(4000);

        assertTrue(capacityRejected);
        assertEquals(9, submittedRecipes.size());
        assertEquals(10, submitCalls);
        assertTrue(discarded > 0);
        assertEquals(0, lastMessage().skipped());
        assertEquals(FoodOrderResultPacket.SUCCESS, lastMessage().code());
    }

    @Test
    void failedFirstBatchDoesNotPreventLaterFoodsFromBeingSubmitted() {
        for (int index = 0; index < 32; index++) unavailable.add(recipe(index));
        submitAll(70);
        runUntilFinished(2000);

        assertEquals(38, submittedRecipes.size());
        assertEquals(38, lastMessage().accepted());
        assertEquals(32, lastMessage().skipped());
        assertEquals(FoodOrderResultPacket.PARTIAL, lastMessage().code());
    }

    @Test
    void submissionFailuresAreCountedWithoutDroppingLaterBatches() {
        rejected.add(recipe(0));
        rejected.add(recipe(34));
        submitAll(40);
        runUntilFinished(2000);

        assertEquals(38, submittedRecipes.size());
        assertEquals(2, lastMessage().skipped());
        assertEquals(FoodOrderResultPacket.PARTIAL, lastMessage().code());
    }

    @Test
    void capacityThatNeverFreesStillTimesOutWithoutConsumingAnyFood() {
        alwaysAtCapacity = true;
        RSIntegrationConfig.FOOD_ORDER_SESSION_TIMEOUT_TICKS.set(20);
        submitAll(3);
        runUntilFinished(200);

        assertTrue(submittedRecipes.isEmpty());
        assertEquals("rsi.food_order.error.timeout", lastMessage().messageKey());
        assertTrue(plans.isEmpty());
        assertTrue(sessions.isEmpty());
    }

    @Test
    void aSecondClickDoesNotCreateAnotherQueueWhileTheFirstIsRunning() {
        submitAll(100);
        FoodOrderService.submit(player, ItemStack.EMPTY, true, null, 1);
        assertEquals("rsi.food_order.error.busy", lastMessage().messageKey());
        runUntilFinished(3000);
        assertEquals(100, submittedRecipes.size());
    }

    @Test
    void singleFoodStillSubmitsOnlyTheSelectedRecipe() {
        catalog(100);
        FoodOrderService.submit(player, new ItemStack(Items.APPLE), false, recipe(80), 1);
        runUntilFinished(200);

        assertEquals(Set.of(recipe(80)), submittedRecipes);
        assertEquals(1, lastMessage().accepted());
    }

    @Test
    void clearingAPlayerReleasesPlansAndPreventsFurtherSubmission() {
        submitAll(100);
        step();
        step();
        step();
        assertFalse(plans.isEmpty());
        FoodOrderService.clearPlayer(player);
        int calls = submitCalls;
        for (int index = 0; index < 100; index++) step();

        assertEquals(calls, submitCalls);
        assertTrue(plans.isEmpty());
        assertTrue(sessions.isEmpty());
    }

    @Test
    void inspectionWithoutProgressTimesOutAndClosesItsSession() {
        inspectionPending = true;
        RSIntegrationConfig.FOOD_ORDER_SESSION_TIMEOUT_TICKS.set(20);
        submitAll(100);
        runUntilFinished(200);

        assertEquals("rsi.food_order.error.timeout", lastMessage().messageKey());
        assertTrue(sessions.isEmpty());
    }

    private void submitAll(int size) {
        catalog(size);
        FoodOrderService.submit(player, ItemStack.EMPTY, true, null, 1);
    }

    private void catalog(int size) {
        List<FoodOrderEntry> entries = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            entries.add(new FoodOrderEntry(new ItemStack(Items.APPLE), FoodOrderKind.READY,
                    recipe(index), Component.empty()));
        }
        FoodOrderSnapshot snapshot = new FoodOrderSnapshot(1, entries, Component.empty(),
                size, size, false, false);
        lists.when(() -> FoodOrderListService.cached(player)).thenReturn(snapshot);
    }

    private void runUntilFinished(int maxTicks) {
        int first = messages.size();
        for (int index = 0; index < maxTicks; index++) {
            step();
            if (messages.size() > first && lastMessage().code() != FoodOrderResultPacket.STARTED) return;
        }
        throw new AssertionError("点餐队列未在预期 tick 数内处理完毕");
    }

    private void step() {
        time.incrementAndGet();
        int before = submitCalls;
        FoodOrderService.tick(server);
        assertTrue(submitCalls - before <= 1, "每 tick 最多提交一个订单");
    }

    private FoodOrderResultPacket lastMessage() {
        return messages.get(messages.size() - 1);
    }

    private static ResourceLocation recipe(int index) {
        return new ResourceLocation("test", "dish_" + index);
    }

    private static ConsumerCapabilities limits(int page, int session, int concurrent) {
        return new ConsumerCapabilities(page, session, 4, 128, 4096, 32768, 32768,
                64, concurrent, 1200, 600, 24000);
    }

    private static <T> ConsumerResult<T> failure(ConsumerDiagnosticCode code) {
        return ConsumerResult.rejected(ConsumerDiagnostic.error(code, "test", true));
    }
}
