package com.huanghuang.rsintegration.mods.goety.magic;

import com.wintercogs.beyonddimensions.api.storage.key.StackKeyRegistry;
import net.minecraftforge.fml.ModList;

/** 在 BD 已加载时注册 RSI 的 Goety 灵魂 Key。 */
public final class GoetySoulKeys {
    private static volatile boolean registered;

    private GoetySoulKeys() {}

    public static void register() {
        if (registered) return;
        if (ModList.get() == null
                || !ModList.get().isLoaded("goety")
                || !ModList.get().isLoaded("beyonddimensions")) return;
        try {
            StackKeyRegistry.getType(GoetySoulStackKey.ID);
        } catch (IllegalArgumentException absent) {
            StackKeyRegistry.registerType(GoetySoulStackKey.INSTANCE);
        } catch (LinkageError unavailable) {
            return;
        }
        registered = true;
    }
}
