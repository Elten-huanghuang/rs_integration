package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.Consumer;

/** 目录 warm-up 与执行请求的有界队列，不包含规划或执行业务。 */
final class CraftRequestQueues {
    static final long CATALOG_TIMEOUT_NANOS = 30_000_000_000L;
    private static final int MAX_WARM_UP_REQUESTS = 128;
    private static final int MAX_EXECUTION_REQUESTS = 64;

    private static final DeferredCraftRequestQueue<Consumer<ServerPlayer>> WARM_UP =
            new DeferredCraftRequestQueue<>(MAX_WARM_UP_REQUESTS);
    private static final DeferredCraftRequestQueue<Consumer<ServerPlayer>> EXECUTION =
            new DeferredCraftRequestQueue<>(MAX_EXECUTION_REQUESTS);

    private CraftRequestQueues() {}

    static boolean offerWarmUp(
            DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request) {
        return WARM_UP.offer(request);
    }

    static boolean offerExecution(
            DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> request) {
        return EXECUTION.offer(request);
    }

    static DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> pollWarmUp() {
        return WARM_UP.poll();
    }

    static DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> pollExecution() {
        return EXECUTION.poll();
    }

    static DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> pollExpiredWarmUp(
            long nowNanos) {
        return WARM_UP.pollExpired(nowNanos, CATALOG_TIMEOUT_NANOS);
    }

    static DeferredCraftRequestQueue.Entry<Consumer<ServerPlayer>> pollExpiredExecution(
            long nowNanos) {
        return EXECUTION.pollExpired(nowNanos, CATALOG_TIMEOUT_NANOS);
    }

    static int warmUpSize() {
        return WARM_UP.size();
    }

    static int executionSize() {
        return EXECUTION.size();
    }

    static void removePlayer(UUID playerId) {
        WARM_UP.removePlayer(playerId);
        EXECUTION.removePlayer(playerId);
    }

    static void clear() {
        WARM_UP.clear();
        EXECUTION.clear();
    }
}
