package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.magic.route.Channel;
import com.huanghuang.rsintegration.magic.route.MagicResourceRouter;
import com.huanghuang.rsintegration.magic.route.MagicStorageBackendDescriptor;

import java.util.List;

/**
 * 启动期的路由自检。
 *
 * <p>这里只做「注册层面」的检查：某个资源一个后端都没注册。模组可以单独安装
 * （例如只有 RS 没有 Goety），所以缺后端只 WARN，不 fail-fast。</p>
 *
 * <p>「有后端但读不出来」不在启动期判断：它取决于玩家当前连的终端和在线的网络，
 * 必须在运行期由 {@code GoetyBatchDelegate.validateAndInit} 报告。</p>
 */
public final class MagicStorageBackendHealth {
    private MagicStorageBackendHealth() {}

    /** 某资源没有任何后端注册时给出 WARN；重复调用不会重复刷屏。 */
    public static void warnOnMissingBackend(MagicResourceRouter router) {
        if (router == null) return;
        for (MagicResourceKind kind : MagicResourceKind.values()) {
            List<MagicStorageBackendDescriptor> candidates = router.candidates(kind);
            if (!candidates.isEmpty()) continue;
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Magic] 资源 {} 没有任何后端注册；预览只会显示需求量。",
                    kind.id());
        }
    }

    /** 诊断用：该资源当前声明的后端里有没有可用路径。 */
    public static boolean hasUsableChannel(MagicResourceRouter router, MagicResourceKind kind) {
        if (router == null) return false;
        for (MagicStorageBackendDescriptor descriptor : router.candidates(kind)) {
            if (descriptor.channel(kind) != Channel.NONE) return true;
        }
        return false;
    }
}
