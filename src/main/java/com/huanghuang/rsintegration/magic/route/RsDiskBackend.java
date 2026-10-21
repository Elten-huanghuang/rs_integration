package com.huanghuang.rsintegration.magic.route;

import com.huanghuang.rsintegration.storage.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.storage.disk.rs.UnifiedDiskManager;
import com.huanghuang.rsintegration.magic.MagicDiskStore;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceNetworkPool;
import com.huanghuang.rsintegration.magic.MagicResourceReservations;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * RSI 归墟盘后端，声明支持全部三种魔法资源。
 *
 * <p>魔法资源不进 RS 缓存，没有网络级索引，所以「存量」只能是所有已挂载归墟盘
 * 之和，与本链路选中的某个网络无关——这正是 {@link Channel#DISK_MOUNTED} 的含义。
 * 唯一例外是 mana/source 这两种资源已经从递归供能链路退出，只保留手动充放。</p>
 */
public final class RsDiskBackend implements MagicStorageBackend {
    public static final ResourceLocation ID = new ResourceLocation("rs_integration", "disk");
    /** priority 只在全局池模式下做并列兜底；归墟盘优先是刻意选择。 */
    public static final int PRIORITY = 0;

    private static final Set<MagicResourceKind> KINDS = EnumSet.of(
            MagicResourceKind.MANA, MagicResourceKind.SOURCE, MagicResourceKind.SOUL);

    /** 声明式描述符；注册期只读声明，不触碰库存。 */
    public static MagicStorageBackendDescriptor builtInDescriptor() {
        return RsDiskBackendDescriptor.INSTANCE;
    }

    private final ServerPlayer player;
    private final MagicScope scope;

    public RsDiskBackend(ServerPlayer player, MagicScope scope) {
        this.player = player;
        this.scope = scope;
    }

    @Override
    public MagicStorageBackendDescriptor descriptor() {
        return RsDiskBackendDescriptor.INSTANCE;
    }

    @Override
    public boolean reachable(MagicResourceKind kind) {
        requireSupported(kind);
        // 「没有挂载任何盘」是空，不是读不出来：管理器存在就算可达。
        return manager() != null;
    }

    @Override
    public MagicScopeHandle bind() {
        List<UnifiedDiskCore> disks = MagicDiskStore.mountedDisks(manager());
        return new MagicScopeHandle(ID, disks.isEmpty() ? java.util.Map.of()
                : java.util.Map.of("disks", disks));
    }

    @Override
    public long amount(MagicResourceKind kind, MagicScopeHandle handle) {
        requireSupported(kind);
        if (!handle.belongsTo(ID)) return 0;
        if (!(handle.get("disks") instanceof List<?> raw)) return 0;
        List<UnifiedDiskCore> disks = raw.stream()
                .filter(UnifiedDiskCore.class::isInstance)
                .map(UnifiedDiskCore.class::cast)
                .toList();
        return MagicDiskStore.available(disks, kind);
    }

    @Override
    public MagicResourceNetworkPool reserve(MagicResourceKind kind, long requested,
                                            MagicScopeHandle handle) {
        requireSupported(kind);
        return MagicResourceReservations.reserve(manager(), kind, requested);
    }

    @Override
    public long insert(MagicResourceKind kind, long amount, MagicScopeHandle handle) {
        requireSupported(kind);
        return MagicDiskStore.insert(MagicDiskStore.mountedDisks(manager()), kind, amount, true);
    }

    @Override
    public long extract(MagicResourceKind kind, long amount, MagicScopeHandle handle) {
        requireSupported(kind);
        return MagicDiskStore.extract(MagicDiskStore.mountedDisks(manager()), kind, amount, true);
    }

    private UnifiedDiskManager manager() {
        return player == null ? null : UnifiedDiskManager.existing(player.server);
    }

    /** 描述符：声明式注册，注册期不触碰任何库存。 */
    private static final class RsDiskBackendDescriptor implements MagicStorageBackendDescriptor {
        private static final RsDiskBackendDescriptor INSTANCE = new RsDiskBackendDescriptor();

        private RsDiskBackendDescriptor() {}

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
            return scope.hasBackend("refinedstorage");
        }

        @Override public Channel channel(MagicResourceKind kind) {
            return KINDS.contains(kind) ? Channel.DISK_MOUNTED : Channel.NONE;
        }

        @Override public MagicStorageBackend instantiate(MagicScope scope) {
            return new RsDiskBackend(scope.player(), scope);
        }
    }
}
