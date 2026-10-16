package com.huanghuang.rsintegration.magic.route;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.SessionCraftStorageEndpoint;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceNetworkPool;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 路由层骨架：空间管辖决定权威后端，priority 不参与；缺后端/缺映射必须显式失败。
 */
class MagicResourceRouterTest extends BootstrapTest {
    private MagicResourceRouter router;
    private ServerPlayer player;

    @BeforeEach
    void setUp() {
        router = new MagicResourceRouter();
        player = mock(ServerPlayer.class);
        router.register(fake("rsintegration:disk", 0,
                EnumSet.of(MagicResourceKind.SOUL, MagicResourceKind.MANA,
                        MagicResourceKind.SOURCE),
                "refinedstorage", Channel.DISK_MOUNTED, true));
        router.register(fake("beyonddimensions:network", 10,
                EnumSet.of(MagicResourceKind.SOUL),
                "beyonddimensions", Channel.BACKEND_LOCAL, true));
    }

    @Test
    void candidatesAreSortedByPriority() {
        assertEquals(2, router.candidates(MagicResourceKind.SOUL).size());
        assertEquals(0, router.candidates(MagicResourceKind.SOUL).get(0).priority());
        assertEquals(1, router.candidates(MagicResourceKind.MANA).size());
        assertTrue(router.candidates(MagicResourceKind.SOURCE).stream()
                .allMatch(d -> d.priority() == 0));
    }

    @Test
    void soulOnRsTerminalUsesTheDiskBackend() {
        BackendResolution resolution = router.resolve(MagicResourceKind.SOUL,
                scope("refinedstorage"));

        assertEquals(BackendResolution.Kind.AVAILABLE, resolution.kind());
        assertEquals("rsintegration:disk", resolution.backend().id().toString());
        assertEquals(Channel.DISK_MOUNTED, resolution.channel());
    }

    @Test
    void soulOnBdTerminalUsesTheNetworkBackend() {
        BackendResolution resolution = router.resolve(MagicResourceKind.SOUL,
                scope("beyonddimensions"));

        assertEquals(BackendResolution.Kind.AVAILABLE, resolution.kind());
        assertEquals("beyonddimensions:network", resolution.backend().id().toString());
        assertEquals(Channel.BACKEND_LOCAL, resolution.channel());
    }

    @Test
    void manaIsAvailableWithoutGoingThroughAmbiguous() {
        BackendResolution resolution = router.resolve(MagicResourceKind.MANA,
                scope("refinedstorage"));

        assertEquals(BackendResolution.Kind.AVAILABLE, resolution.kind());
        assertEquals("rsintegration:disk", resolution.backend().id().toString());
        // 单一候选不该落进消歧分支。
        assertNotEquals(BackendResolution.Kind.AMBIGUOUS, resolution.kind());
    }

    @Test
    void soulWithoutEndpointNeverAggregatesOrPicksByPriority() {
        BackendResolution resolution = router.resolve(MagicResourceKind.SOUL,
                MagicScope.of(player, null));

        // 无端点时 soul 有两本互不相干的账，不能按 priority 偷选第一本。
        assertNotEquals(BackendResolution.Kind.AVAILABLE, resolution.kind());
        assertEquals(BackendResolution.Kind.UNREACHABLE, resolution.kind());
        assertFalse(resolution.resolved());
    }

    @Test
    void unknownEndpointIsAmbiguousNotSilentlyResolved() {
        BackendResolution resolution = router.resolve(MagicResourceKind.SOUL,
                scope("someothermod"));

        assertEquals(BackendResolution.Kind.AMBIGUOUS, resolution.kind());
    }

    @Test
    void unregisteredResourceIsUnsupported() {
        MagicResourceRouter empty = new MagicResourceRouter();
        BackendResolution resolution = empty.resolve(MagicResourceKind.SOUL, scope("refinedstorage"));

        assertEquals(BackendResolution.Kind.UNSUPPORTED, resolution.kind());
        assertTrue(resolution.candidates().isEmpty());
    }

    @Test
    void unreachableNamedBackendIsUnreachableNotAvailable() {
        MagicResourceRouter inner = new MagicResourceRouter();
        inner.register(fake("rsintegration:disk", 0,
                EnumSet.of(MagicResourceKind.SOUL), "refinedstorage",
                Channel.DISK_MOUNTED, false));

        assertEquals(BackendResolution.Kind.UNREACHABLE,
                inner.resolve(MagicResourceKind.SOUL, scope("refinedstorage")).kind());
    }

    @Test
    void resolveIsPureAcrossConsecutiveCalls() {
        BackendResolution first = router.resolve(MagicResourceKind.SOUL, scope("beyonddimensions"));
        BackendResolution second = router.resolve(MagicResourceKind.SOUL, scope("beyonddimensions"));

        assertSame(first.backend(), second.backend());
        assertEquals(first.backend().id(), second.backend().id());
    }

    @Test
    void pinnedPolicyOnlyAppliesWhenNoBackendOwnsTheScope() {
        router.setPolicy(AuthorityPolicy.pinned(new ResourceLocation("beyonddimensions", "network")));

        // 有空间归属时仍按空间管辖，不受 pinned 影响。
        assertEquals("rsintegration:disk",
                router.resolve(MagicResourceKind.SOUL, scope("refinedstorage")).backend().id().toString());
        // 没有空间归属时才轮到 pinned。
        assertEquals("beyonddimensions:network",
                router.resolve(MagicResourceKind.SOUL, MagicScope.of(player, null))
                        .backend().id().toString());
    }

    @Test
    void pinnedPolicyCannotRouteKindsTheBackendDoesNotDeclare() {
        router.setPolicy(AuthorityPolicy.pinned(new ResourceLocation("beyonddimensions", "network")));

        // BD 没有声明支持 mana，配置不能凭空把它变成 mana 的后端；
        // mana 唯一的候选是归墟盘，所以结论仍是归墟盘。
        BackendResolution resolution =
                router.resolve(MagicResourceKind.MANA, MagicScope.of(player, null));
        assertEquals(BackendResolution.Kind.AVAILABLE, resolution.kind());
        assertEquals("rsintegration:disk", resolution.backend().id().toString());
    }

    @Test
    void unsupportedKindThrowsInsteadOfReturningZero() {
        MagicStorageBackendDescriptor bd = BdNetworkBackend.builtInDescriptor();
        MagicStorageBackend backend = bd.instantiate(scope("beyonddimensions"));

        assertThrows(UnsupportedOperationException.class,
                () -> backend.amount(MagicResourceKind.MANA, backend.bind()));
        assertThrows(UnsupportedOperationException.class,
                () -> backend.insert(MagicResourceKind.SOURCE, 1, backend.bind()));
    }

    private MagicScope scope(String backend) {
        StorageSession session = mock(StorageSession.class);
        when(session.reference()).thenReturn(new StorageReference(
                new StorageBackendId(backend),
                backend.equals("beyonddimensions") ? "7" : backend + "-network"));
        CraftStorageEndpoint endpoint = new SessionCraftStorageEndpoint(session);
        return MagicScope.of(player, endpoint);
    }

    /** 只用于路由判定的假描述符，不接触真实库存。 */
    private static MagicStorageBackendDescriptor fake(
            String id, int priority, Set<MagicResourceKind> kinds,
            String backendName, Channel channel, boolean reachable) {
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
                return scope.hasBackend(backendName);
            }

            @Override public Channel channel(MagicResourceKind kind) {
                return kinds.contains(kind) ? channel : Channel.NONE;
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
