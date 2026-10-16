package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.magic.route.MagicResourceRouter;
import com.huanghuang.rsintegration.magic.route.RsDiskBackend;
import com.huanghuang.rsintegration.magic.route.BdNetworkBackend;

/**
 * 魔法资源路由的装配入口。
 *
 * <p>本 Goal 只装两个声明式描述符：{@code rsintegration:disk} 支持全部三种资源，
 * {@code beyonddimensions:network} 只支持灵魂。装配期不触碰任何库存或原生 API，
 * 所以放在通用初始化里是安全的。</p>
 */
public final class MagicStorageBackends {
    private MagicStorageBackends() {}

    /** 注册内置后端描述符；重复调用是幂等的。 */
    public static void registerBuiltIns(MagicResourceRouter router) {
        if (router == null) return;
        router.register(RsDiskBackend.builtInDescriptor());
        router.register(BdNetworkBackend.builtInDescriptor());
    }
}
