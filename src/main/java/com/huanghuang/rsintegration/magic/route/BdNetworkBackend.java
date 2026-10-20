package com.huanghuang.rsintegration.magic.route;

import com.huanghuang.rsintegration.magic.BdReservationBridge;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceNetworkPool;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumSet;
import java.util.Set;

/**
 * Beyond Dimensions 网络后端，只声明支持灵魂。
 *
 * <p>mana/source 由 BD 自己的 {@code ManaStackKey} / {@code SourceStackKey} 与桥接
 * 方块承担，RSI 不注册、不路由。这两个资源在这里会由
 * {@link #requireSupported(MagicResourceKind)} 直接抛出，绝不静默返回 0——
 * 「BD 没有这种资源」和「BD 里这种资源是空的」是两件事。</p>
 *
 * <p>访问库存统一走 {@link BdReservationBridge} 的反射边界，按 stack type 的存取
 * 入口而不是按具体类判断，因此 BD 未安装时只会得到「不可达」。</p>
 */
public final class BdNetworkBackend implements MagicStorageBackend {
    public static final ResourceLocation ID =
            new ResourceLocation("beyonddimensions", "network");
    public static final int PRIORITY = 10;

    private static final Set<MagicResourceKind> KINDS = EnumSet.of(MagicResourceKind.SOUL);

    /** 声明式描述符；注册期只读声明，不触碰库存。 */
    public static MagicStorageBackendDescriptor builtInDescriptor() {
        return Descriptor.INSTANCE;
    }
    private final ServerPlayer player;
    private final MagicScope scope;
    private final int networkId;
    private final String sourceKey;

    public BdNetworkBackend(ServerPlayer player, MagicScope scope, int networkId,
                            String sourceKey) {
        this.player = player;
        this.scope = scope;
        this.networkId = networkId;
        this.sourceKey = sourceKey == null ? "" : sourceKey;
    }

    @Override
    public MagicStorageBackendDescriptor descriptor() {
        return Descriptor.INSTANCE;
    }

    /** 端点里的 BD 网络号；不是 BD 端点或解析失败时返回 -1。 */
    public static int networkIdOf(MagicScope scope) {
        if (!scope.hasBackend("beyonddimensions")) return -1;
        try {
            return Integer.parseInt(scope.endpoint().session().reference().networkId());
        } catch (RuntimeException | LinkageError ignored) {
            return -1;
        }
    }

    @Override
    public boolean reachable(MagicResourceKind kind) {
        requireSupported(kind);
        // 读不出来返回 false，而不是让 amount() 报 0。
        return BdReservationBridge.amount(player, networkId) != null;
    }

    @Override
    public MagicScopeHandle bind() {
        return new MagicScopeHandle(ID, java.util.Map.of(
                "networkId", networkId, "sourceKey", sourceKey));
    }

    @Override
    public long amount(MagicResourceKind kind, MagicScopeHandle handle) {
        requireSupported(kind);
        if (!handle.belongsTo(ID)) return 0;
        int id = handle.intValue("networkId", -1);
        String source = handle.stringValue("sourceKey");
        Long raw = BdReservationBridge.amount(player, id);
        if (raw == null) return 0;
        return Math.max(0, raw - BdReservationBridge.reservedFor(source, id));
    }

    @Override
    public MagicResourceNetworkPool reserve(MagicResourceKind kind, long requested,
                                            MagicScopeHandle handle) {
        requireSupported(kind);
        return BdReservationBridge.reserve(player, networkId, requested, sourceKey);
    }

    @Override
    public long insert(MagicResourceKind kind, long amount, MagicScopeHandle handle) {
        requireSupported(kind);
        // 写入失败必须让调用方看到，而不是拿到一个「接受 0」的假成功。
        return BdReservationBridge.insertOrThrow(player, networkId, amount);
    }

    @Override
    public long extract(MagicResourceKind kind, long amount, MagicScopeHandle handle) {
        requireSupported(kind);
        return BdReservationBridge.extractOrThrow(player, networkId, amount);
    }

    /** 描述符：声明式注册，注册期不触碰任何库存。 */
    private static final class Descriptor implements MagicStorageBackendDescriptor {
        private static final Descriptor INSTANCE = new Descriptor();

        private Descriptor() {}

        @Override public ResourceLocation id() {
            return ID;
        }

        @Override public Set<MagicResourceKind> supportedKinds() {
            return KINDS;
        }

        @Override public int priority() {
            return PRIORITY;
        }

        @Override public boolean ownsScope(MagicScope scope) {
            return scope.hasBackend("beyonddimensions") && networkIdOf(scope) >= 0;
        }

        @Override public Channel channel(MagicResourceKind kind) {
            return KINDS.contains(kind) ? Channel.BACKEND_LOCAL : Channel.NONE;
        }

        @Override public MagicStorageBackend instantiate(MagicScope scope) {
            int networkId = networkIdOf(scope);
            String sourceKey = BdReservationBridge.sourceOf(scope.player(), networkId);
            return new BdNetworkBackend(scope.player(), scope, networkId, sourceKey);
        }
    }
}
