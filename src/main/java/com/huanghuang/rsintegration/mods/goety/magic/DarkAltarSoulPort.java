package com.huanghuang.rsintegration.mods.goety.magic;

import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 黑暗祭坛到统一灵魂账本的运行时端口。 */
public final class DarkAltarSoulPort {
    private static final Map<Object, GoetySoulSourceCoordinator> ACTIVE =
            new IdentityHashMap<>();

    private DarkAltarSoulPort() {}

    static void attach(Object altar, Object cage,
                       GoetySoulSourceCoordinator coordinator) {
        synchronized (ACTIVE) {
            ACTIVE.put(altar, coordinator);
        }
        GoetyVirtualSoulAccess.attach(altar, coordinator,
                cage == null ? List.of() : List.of(cage));
    }

    static void detach(Object altar) {
        synchronized (ACTIVE) {
            ACTIVE.remove(altar);
        }
        GoetyVirtualSoulAccess.detach(altar);
    }

    public static boolean isActive(Object altar) {
        return find(altar) != null;
    }

    /** 未接管时返回 -1。 */
    public static int souls(Object altar) {
        GoetySoulSourceCoordinator coordinator = find(altar);
        return coordinator == null ? -1 : SaturatingLongMath.clampToInt(coordinator.available());
    }

    public static boolean consume(Object altar, int amount) {
        GoetySoulSourceCoordinator coordinator = find(altar);
        return coordinator != null && coordinator.consume(amount);
    }

    /** 诅咒笼上的统一账本余额；未接管时返回 -1。 */
    public static int cageSouls(Object cage) {
        return GoetyVirtualSoulAccess.souls(cage);
    }

    public static boolean isCageActive(Object cage) {
        return GoetyVirtualSoulAccess.isActive(cage);
    }

    @Nullable
    private static GoetySoulSourceCoordinator find(Object altar) {
        synchronized (ACTIVE) {
            return ACTIVE.get(altar);
        }
    }
}
