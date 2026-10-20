package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 亡灵火盆到统一灵魂账本的运行时端口。 */
public final class BrazierSoulPort {
    private static final Map<Object, ActiveSource> ACTIVE =
            new IdentityHashMap<>();

    private BrazierSoulPort() {}

    static void attach(Object brazier, GoetySoulSourceCoordinator coordinator,
                       List<Object> candles) {
        attach(brazier, coordinator, candles, null, 0);
    }

    static void attach(Object brazier, GoetySoulSourceCoordinator coordinator,
                       List<Object> candles, @Nullable Object virtualCandle,
                       int virtualCandleSpeed) {
        synchronized (ACTIVE) {
            ACTIVE.put(brazier, new ActiveSource(
                    coordinator, virtualCandle, Math.max(1, virtualCandleSpeed)));
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
        ActiveSource active = find(brazier);
        return active == null ? -1 : SaturatingLongMath.clampToInt(
                active.coordinator().available());
    }

    public static boolean consume(Object brazier, int amount) {
        ActiveSource active = find(brazier);
        return active != null && active.coordinator().consume(amount);
    }

    /** RSI 递归合成无实体烛台时注入的内存烛台；手动合成返回 null。 */
    @Nullable
    public static Object virtualCandle(Object brazier) {
        ActiveSource active = find(brazier);
        return active == null ? null : active.virtualCandle();
    }

    /** 虚拟烛台模拟的推进速度；无虚拟烛台时返回 1。 */
    public static int virtualCandleSpeed(Object brazier) {
        ActiveSource active = find(brazier);
        return active == null || active.virtualCandle() == null
                ? 1 : active.virtualCandleSpeed();
    }

    /** 灵魂烛台上的统一账本余额；未接管时返回 -1。 */
    public static int candleSouls(Object candle) {
        return GoetyVirtualSoulAccess.souls(candle);
    }

    public static boolean isCandleActive(Object candle) {
        return GoetyVirtualSoulAccess.isActive(candle);
    }

    @Nullable
    private static ActiveSource find(Object brazier) {
        synchronized (ACTIVE) {
            return ACTIVE.get(brazier);
        }
    }

    private record ActiveSource(GoetySoulSourceCoordinator coordinator,
                                @Nullable Object virtualCandle,
                                int virtualCandleSpeed) {}
}
