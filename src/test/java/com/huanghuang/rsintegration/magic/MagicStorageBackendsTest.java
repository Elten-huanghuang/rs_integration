package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.magic.route.Channel;
import com.huanghuang.rsintegration.magic.route.MagicResourceRouter;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动装配：路由表要覆盖三种资源，且 mana/source 的路径是「已挂载归墟盘」。
 */
class MagicStorageBackendsTest extends BootstrapTest {
    @Test
    void builtInsDeclareTheExpectedKindCoverage() {
        MagicResourceRouter router = new MagicResourceRouter();
        MagicStorageBackends.registerBuiltIns(router);

        assertEquals(1, router.candidates(MagicResourceKind.MANA).size());
        assertEquals(1, router.candidates(MagicResourceKind.SOURCE).size());
        assertEquals(2, router.candidates(MagicResourceKind.SOUL).size());
    }

    @Test
    void diskBackendServesEveryKindOverTheMountedDisksChannel() {
        MagicResourceRouter router = new MagicResourceRouter();
        MagicStorageBackends.registerBuiltIns(router);

        for (MagicResourceKind kind : MagicResourceKind.values()) {
            assertEquals(Channel.DISK_MOUNTED,
                    router.candidates(kind).get(0).channel(kind));
        }
        assertEquals(Channel.BACKEND_LOCAL,
                router.candidates(MagicResourceKind.SOUL).get(1).channel(MagicResourceKind.SOUL));
    }

    @Test
    void networkBackendRefusesKindsItDoesNotDeclare() {
        MagicResourceRouter router = new MagicResourceRouter();
        MagicStorageBackends.registerBuiltIns(router);

        var bd = router.candidates(MagicResourceKind.SOUL).stream()
                .filter(d -> d.id().toString().equals("beyonddimensions:network"))
                .findFirst()
                .orElseThrow();
        assertEquals(Channel.NONE, bd.channel(MagicResourceKind.MANA));
        assertEquals(Channel.NONE, bd.channel(MagicResourceKind.SOURCE));
        assertTrue(MagicStorageBackendHealth.hasUsableChannel(router, MagicResourceKind.SOUL));
    }

    @Test
    void registeringTwiceKeepsTheTableStable() {
        MagicResourceRouter router = new MagicResourceRouter();
        MagicStorageBackends.registerBuiltIns(router);
        MagicStorageBackends.registerBuiltIns(router);

        assertEquals(2, router.descriptors().size());
    }
}
