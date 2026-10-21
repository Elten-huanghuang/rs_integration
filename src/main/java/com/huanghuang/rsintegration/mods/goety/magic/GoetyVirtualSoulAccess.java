package com.huanghuang.rsintegration.mods.goety.magic;

import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.util.Reflect;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Goety 原生灵魂容器到统一账本的运行时映射。
 *
 * <p>黑暗祭坛接管诅咒笼，亡灵火盆接管灵魂烛台。读取原生容器时优先返回
 * 统一账本的可用量，扣除时也由账本统一结算，因此调用方不需要感知 RS/BD
 * 与原生来源的区别。</p>
 */
public final class GoetyVirtualSoulAccess {
    private static final Map<Object, GoetySoulSourceCoordinator> SOURCES =
            new IdentityHashMap<>();
    private static final Map<Object, SourceGroup> GROUPS = new IdentityHashMap<>();
    private static final ThreadLocal<Integer> BYPASS_DEPTH =
            ThreadLocal.withInitial(() -> 0);
    private static final Object LOCK = new Object();

    private GoetyVirtualSoulAccess() {}

    static void attach(Object owner, GoetySoulSourceCoordinator coordinator,
                       Iterable<?> sources) {
        if (owner == null || coordinator == null) return;
        synchronized (LOCK) {
            detachLocked(owner);
            IdentityHashMap<Object, GoetySoulSourceCoordinator> groupSources =
                    new IdentityHashMap<>();
            for (Object source : sources) {
                if (source == null) continue;
                SOURCES.put(source, coordinator);
                groupSources.put(source, coordinator);
            }
            GROUPS.put(owner, new SourceGroup(coordinator, groupSources));
        }
    }

    static void detach(Object owner) {
        if (owner == null) return;
        synchronized (LOCK) {
            detachLocked(owner);
        }
    }

    /** 未接管时返回 -1。 */
    public static int souls(Object source) {
        if (isBypassed()) return -1;
        GoetySoulSourceCoordinator coordinator = find(source);
        return coordinator == null ? -1 : SaturatingLongMath.clampToInt(coordinator.available());
    }

    public static boolean isActive(Object source) {
        return !isBypassed() && find(source) != null;
    }

    public static boolean consume(Object source, int amount) {
        if (isBypassed()) return false;
        GoetySoulSourceCoordinator coordinator = find(source);
        if (coordinator == null) return false;
        return amount <= 0 || coordinator.consume(amount);
    }

    /** 绕过统一账本，直接读取原生容器余额。 */
    static int physicalSouls(Object source) {
        return runBypassed(() -> Reflect.<Integer>invoke(source, "getSouls").orElse(0));
    }

    /** 绕过统一账本，直接扣除原生容器余额。 */
    static void physicalDecrease(Object source, int amount) {
        runBypassed(() -> {
            Reflect.invokeInt(source, "decreaseSouls", amount);
            return 0;
        });
    }

    private static boolean isBypassed() {
        return BYPASS_DEPTH.get() > 0;
    }

    private static <T> T runBypassed(Supplier<T> operation) {
        BYPASS_DEPTH.set(BYPASS_DEPTH.get() + 1);
        try {
            return operation.get();
        } finally {
            int depth = BYPASS_DEPTH.get() - 1;
            if (depth <= 0) BYPASS_DEPTH.remove();
            else BYPASS_DEPTH.set(depth);
        }
    }

    @Nullable
    private static GoetySoulSourceCoordinator find(Object source) {
        if (source == null) return null;
        synchronized (LOCK) {
            return SOURCES.get(source);
        }
    }

    private static void detachLocked(Object owner) {
        SourceGroup group = GROUPS.remove(owner);
        if (group == null) return;
        for (Map.Entry<Object, GoetySoulSourceCoordinator> entry
                : group.sources.entrySet()) {
            if (SOURCES.get(entry.getKey()) == entry.getValue()) {
                SOURCES.remove(entry.getKey());
            }
        }
    }

    private record SourceGroup(
            GoetySoulSourceCoordinator coordinator,
            IdentityHashMap<Object, GoetySoulSourceCoordinator> sources) {}
}
