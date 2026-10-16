package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * RS 与 BD 是彼此独立的灵魂存储后端。
 *
 * <p>RS 通过混合终端的 SOUL 通道提供库存与交互；BD 需要额外把自己的
 * StackKey 注册进 BD 的资源表。两者都只要求同时安装 Goety，不要求另一端存在。</p>
 */
public final class GoetySoulBackends {
    private static volatile boolean rsRegistered;
    private static volatile boolean bdRegistered;

    private GoetySoulBackends() {}

    public static void register() {
        if (ModList.get() == null || !ModList.get().isLoaded("goety")) return;

        if (ModList.get().isLoaded("refinedstorage")) {
            // RS 混合终端通过 SOUL 资源枚举直接接入，不依赖 BD 的 StackKeyRegistry。
            rsRegistered = true;
        }
        if (ModList.get().isLoaded("beyonddimensions")) {
            registerBeyondDimensions();
        }
    }

    public static boolean rsRegistered() {
        return rsRegistered;
    }

    public static boolean bdRegistered() {
        return bdRegistered;
    }

    private static void registerBeyondDimensions() {
        try {
            Class<?> keys = Class.forName(
                    "com.huanghuang.rsintegration.magic.GoetySoulKeys");
            Method register = keys.getMethod("register");
            register.invoke(null);
            bdRegistered = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            // 第三方 API 版本不匹配时只关闭 BD 灵魂功能，不影响 RS 后端和通用初始化。
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Goety] 跳过 Beyond Dimensions 灵魂 Key 注册: {}",
                    unavailable.toString());
        }
    }
}
