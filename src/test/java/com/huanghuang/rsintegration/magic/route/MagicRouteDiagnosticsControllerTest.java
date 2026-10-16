package com.huanghuang.rsintegration.magic.route;

import com.huanghuang.rsintegration.magic.MagicResourceDiagnostics;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceNetworkPool;
import com.huanghuang.rsintegration.magic.MagicResourceRouteCounters;
import com.huanghuang.rsintegration.magic.MagicStorageBackends;
import com.huanghuang.rsintegration.magic.SoulPlanPreview;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断面（Goal7）：计数器只被 resolveCounted(record=true) 改动，
 * 命令用的 record=false 不得污染计数，且描述不读库存。
 */
class MagicRouteDiagnosticsControllerTest extends BootstrapTest {

    @BeforeEach
    void resetCounters() {
        MagicResourceRouteCounters.reset();
    }

    @Test
    void recordingResolutionCountsTheStateOnce() {
        MagicResourceRouter router = new MagicResourceRouter();
        router.register(fake("test:fake", 0, EnumSet.of(MagicResourceKind.MANA), true));

        BackendResolution resolution = router.resolveCounted(
                MagicResourceKind.MANA, MagicScope.of(null, null), true);

        assertEquals(BackendResolution.Kind.AVAILABLE, resolution.kind());
        assertEquals(1L, MagicResourceRouteCounters.routeTotal());
        assertEquals(0L, MagicResourceRouteCounters.unsupportedTotal());
        assertEquals(0L, MagicResourceRouteCounters.unreachableTotal());
    }

    @Test
    void unsupportedHasItsOwnCounter() {
        MagicResourceRouter router = new MagicResourceRouter();
        router.register(fake("test:fake", 0, EnumSet.of(MagicResourceKind.MANA), true));

        // 没有后端声明 SOURCE，判 UNSUPPORTED，而不是「可用 0」。
        router.resolveCounted(MagicResourceKind.SOURCE, MagicScope.of(null, null), true);

        assertEquals(1L, MagicResourceRouteCounters.unsupportedTotal());
        assertEquals(1L, MagicResourceRouteCounters.routeTotal());
    }

    @Test
    void unreachableHasItsOwnCounter() {
        MagicResourceRouter router = new MagicResourceRouter();
        router.register(fake("test:fake", 0, EnumSet.of(MagicResourceKind.MANA), false));

        router.resolveCounted(MagicResourceKind.MANA, MagicScope.of(null, null), true);

        assertEquals(1L, MagicResourceRouteCounters.unreachableTotal());
        assertEquals(0L, MagicResourceRouteCounters.unsupportedTotal());
    }

    @Test
    void diagnosticLookupsDoNotMoveTheCounters() {
        MagicResourceRouter router = new MagicResourceRouter();
        router.register(fake("test:fake", 0, EnumSet.of(MagicResourceKind.MANA), true));

        router.resolveCounted(MagicResourceKind.MANA, MagicScope.of(null, null), false);
        router.resolveCounted(MagicResourceKind.MANA, MagicScope.of(null, null), false);

        assertEquals(0L, MagicResourceRouteCounters.routeTotal());
        assertEquals("", MagicResourceRouteCounters.lastRoute());
    }

    @Test
    void descriptionNamesTheBackendAndChannelWithoutTouchingInventory() {
        MagicResourceRouter router = new MagicResourceRouter();
        router.register(fake("test:fake", 0, EnumSet.of(MagicResourceKind.MANA), true));
        MagicScope scope = MagicScope.of(null, null);

        BackendResolution resolution = router.resolve(MagicResourceKind.MANA, scope);
        String text = MagicResourceDiagnostics.describeRoute(scope, resolution);

        assertTrue(text.contains("权威后端=test:fake"), text);
        assertTrue(text.contains("状态=AVAILABLE"), text);
        assertTrue(text.contains("通道=DISK_MOUNTED"), text);
        assertTrue(text.contains("链路=无端点"), text);
    }

    @Test
    void previewReportsUnreachableRatherThanZeroWhenThereIsNoLink() {
        SoulPlanPreview.Estimate estimate = SoulPlanPreview.estimate(null, null, 10);

        assertFalse(estimate.reachable());
        assertEquals(SoulPlanPreview.SourceKind.UNREACHABLE, estimate.source());
    }

    @Test
    void routeTableIsReadableWithOnlyBuiltInsRegistered() {
        // 这里不调 logRouteTable：它要拿模组的 LOGGER，而单测环境没有加载上下文，
        // 触发 RSIntegrationMod 静态初始化会失败。启动期那次调用在 onCommonSetup 里。
        MagicResourceRouter router = new MagicResourceRouter();
        MagicStorageBackends.registerBuiltIns(router);

        assertEquals(2, router.descriptors().size());
        assertEquals(2, router.candidates(MagicResourceKind.SOUL).size());
        assertEquals(1, router.candidates(MagicResourceKind.MANA).size());
    }

    /** 只用于路由判定的假描述符，不接触真实库存。 */
    private static MagicStorageBackendDescriptor fake(
            String id, int priority, Set<MagicResourceKind> kinds, boolean reachable) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return new MagicStorageBackendDescriptor() {
            @Override public ResourceLocation id() {
                return location;
            }

            @Override public Set<MagicResourceKind> supportedKinds() {
                return kinds;
            }

            @Override public int priority() {
                return priority;
            }

            @Override public boolean ownsScope(MagicScope scope) {
                return false;
            }

            @Override public Channel channel(MagicResourceKind kind) {
                return kinds.contains(kind) ? Channel.DISK_MOUNTED : Channel.NONE;
            }

            @Override public MagicStorageBackend instantiate(MagicScope scope) {
                MagicStorageBackendDescriptor self = this;
                return new MagicStorageBackend() {
                    @Override public MagicStorageBackendDescriptor descriptor() {
                        return self;
                    }

                    @Override public boolean reachable(MagicResourceKind kind) {
                        requireSupported(kind);
                        return reachable;
                    }

                    @Override public MagicScopeHandle bind() {
                        return MagicScopeHandle.of(location);
                    }

                    @Override public int amount(MagicResourceKind kind, MagicScopeHandle handle) {
                        requireSupported(kind);
                        return 0;
                    }

                    @Override public MagicResourceNetworkPool reserve(MagicResourceKind kind,
                                                                      int requested,
                                                                      MagicScopeHandle handle) {
                        requireSupported(kind);
                        return emptyPool();
                    }

                    @Override public int insert(MagicResourceKind kind, int amount,
                                                MagicScopeHandle handle) {
                        requireSupported(kind);
                        return 0;
                    }

                    @Override public int extract(MagicResourceKind kind, int amount,
                                                 MagicScopeHandle handle) {
                        requireSupported(kind);
                        return 0;
                    }
                };
            }
        };
    }

    private static MagicResourceNetworkPool emptyPool() {
        return new MagicResourceNetworkPool() {
            @Override public int remaining() {
                return 0;
            }

            @Override public int withdraw(int requested) {
                return 0;
            }

            @Override public void refund(int amount) {
            }

            @Override public void close() {
            }
        };
    }
}
