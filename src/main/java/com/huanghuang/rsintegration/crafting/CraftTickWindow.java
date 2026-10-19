package com.huanghuang.rsintegration.crafting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 同一服务器 tick 内所有即时提交和常规链推进共享的执行预算。 */
final class CraftTickWindow {
    private final long serverTick;
    private final long deadlineNanos;
    private final VanillaCraftingTickBudget vanillaBudget;
    private final Map<UUID, Integer> chainUsage = new HashMap<>();
    private final Set<UUID> advancedChains = new HashSet<>();

    CraftTickWindow(long serverTick, long startedNanos, long budgetNanos, int globalVanillaLimit) {
        this.serverTick = serverTick;
        this.deadlineNanos = saturatedAdd(startedNanos, Math.max(1L, budgetNanos));
        this.vanillaBudget = new VanillaCraftingTickBudget(
                Math.max(1, globalVanillaLimit));
    }

    long serverTick() {
        return serverTick;
    }

    boolean hasTime() {
        return System.nanoTime() < deadlineNanos;
    }

    int remainingVanillaOperations(UUID craftId, int perChainLimit) {
        int used = chainUsage.getOrDefault(craftId, 0);
        return Math.max(0, Math.min(
                Math.max(0, perChainLimit) - used,
                vanillaBudget.remaining()));
    }

    VanillaCraftingTickBudget.ChainAllowance allowance(UUID craftId, int perChainLimit) {
        int used = chainUsage.getOrDefault(craftId, 0);
        return vanillaBudget.allowance(perChainLimit, used);
    }

    void record(UUID craftId, int claimedOperations) {
        if (claimedOperations <= 0) return;
        chainUsage.merge(craftId, claimedOperations, Integer::sum);
    }

    void markAdvanced(UUID craftId) {
        advancedChains.add(craftId);
    }

    boolean alreadyAdvanced(UUID craftId) {
        return advancedChains.contains(craftId);
    }

    int usedVanillaOperations() {
        return vanillaBudget.used();
    }

    int vanillaOperationLimit() {
        return vanillaBudget.limit();
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
