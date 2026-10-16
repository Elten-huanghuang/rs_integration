package com.huanghuang.rsintegration.magic;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 亡灵火盆到统一灵魂账本的运行时端口。 */
public final class BrazierSoulPort {
    private static final Map<Object, GoetySoulSourceCoordinator> ACTIVE =
            new IdentityHashMap<>();

    private BrazierSoulPort() {}

    static void attach(Object brazier, GoetySoulSourceCoordinator coordinator,
                       List<Object> candles) {
        synchronized (ACTIVE) {
            ACTIVE.put(brazier, coordinator);
        }
        GoetyVirtualSoulAccess.attach(
                brazier, coordinator, candles == null ? List.of() : candles);
    }

    static void detach(Object brazier) {
        synchronized (ACTIVE) {
            ACTIVE.remove(brazier);
        }
        GoetyVirtualSoulAccess.detach(brazier);
    }

    public static boolean isActive(Object brazier) {
        return find(brazier) != null;
    }

    /** 未接管时返回 -1。 */
    public static int souls(Object brazier) {
        GoetySoulSourceCoordinator coordinator = find(brazier);
        return coordinator == null ? -1 : coordinator.available();
    }

    public static boolean consume(Object brazier, int amount) {
        GoetySoulSourceCoordinator coordinator = find(brazier);
        return coordinator != null && coordinator.consume(amount);
    }

    /** 灵魂烛台上的统一账本余额；未接管时返回 -1。 */
    public static int candleSouls(Object candle) {
        return GoetyVirtualSoulAccess.souls(candle);
    }

    public static boolean isCandleActive(Object candle) {
        return GoetyVirtualSoulAccess.isActive(candle);
    }

    @Nullable
    private static GoetySoulSourceCoordinator find(Object brazier) {
        synchronized (ACTIVE) {
            return ACTIVE.get(brazier);
        }
    }
}
