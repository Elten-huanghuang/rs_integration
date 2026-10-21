package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.storage.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.storage.disk.rs.UnifiedDiskManager;
import net.minecraft.server.MinecraftServer;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.huanghuang.rsintegration.storage.disk.rs.UnifiedMountCoordinator;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 魔法资源的网络侧入口：以当前已挂载的归墟盘为准。
 *
 * <p>魔法资源不在 RS 的物品、流体缓存里，网络对象只表示连接关系，不保存魔法
 * 库存副本，所以这里统一按“管理器里已挂载的盘”读写，不按网络过滤。</p>
 */
public final class MagicResourceEndpoint {
    private MagicResourceEndpoint() {}

    /** 当前已挂载的归墟盘；按盘 ID 排序，顺序稳定。 */
    public static List<UnifiedDiskCore> disks(@Nullable MinecraftServer server) {
        return MagicDiskStore.mountedDisks(UnifiedDiskManager.existing(server));
    }

    /** 该资源的总库存。 */
    public static long total(@Nullable MinecraftServer server, MagicResourceKind kind) {
        return MagicDiskStore.total(disks(server), kind);
    }

    public static long total(@Nullable INetwork network, MagicResourceKind kind) {
        return MagicDiskStore.total(networkDisks(network), kind);
    }

    /** 绑定网络中所有已挂载归墟盘对该资源提供的总容量。 */
    public static long capacity(@Nullable INetwork network, MagicResourceKind kind) {
        return MagicDiskStore.capacity(networkDisks(network), kind);
    }

    /** 该资源的可用量（库存扣除其他任务尚未使用的预留）。 */
    public static long available(@Nullable MinecraftServer server, MagicResourceKind kind) {
        return MagicDiskStore.available(disks(server), kind);
    }

    /** 指定 RS 网络中扣除任务预留后的可用量。 */
    public static long available(@Nullable INetwork network, MagicResourceKind kind) {
        return MagicDiskStore.available(networkDisks(network), kind);
    }

    /** 在当前已挂载的盘上预留；返回的账本必须由调用方关闭。 */
    public static MagicResourceReservations.Pool reserve(@Nullable MinecraftServer server,
                                                         MagicResourceKind kind, long requested) {
        return MagicResourceReservations.reserve(UnifiedDiskManager.existing(server), kind, requested);
    }

    /** 在指定 RS 网络中预留；预留凭据只操作该网络的归墟盘。 */
    public static MagicResourceNetworkPool reserve(@Nullable INetwork network,
                                                   MagicResourceKind kind, long requested) {
        return MagicResourceReservations.reserve(networkDisks(network), kind, requested);
    }

    /** 充入库存，返回实际接收量。 */
    public static long insert(@Nullable MinecraftServer server, MagicResourceKind kind,
                              long requested, boolean perform) {
        return MagicDiskStore.insert(disks(server), kind, requested, perform);
    }

    public static long insert(@Nullable INetwork network, MagicResourceKind kind,
                              long requested, boolean perform) {
        return MagicDiskStore.insert(networkDisks(network), kind, requested, perform);
    }

    /** 从库存取出，返回实际取出量；会把其他任务的预留留在盘上。 */
    public static long extract(@Nullable MinecraftServer server, MagicResourceKind kind,
                               long requested, boolean perform) {
        return MagicResourceReservations.extract(UnifiedDiskManager.existing(server), kind, requested, perform);
    }

    public static long extract(@Nullable INetwork network, MagicResourceKind kind,
                               long requested, boolean perform) {
        return MagicResourceReservations.extract(networkDisks(network), kind, requested, perform);
    }

    private static List<UnifiedDiskCore> networkDisks(@Nullable INetwork network) {
        if (network == null || network.getLevel() == null) return List.of();
        if (!(network.getLevel() instanceof ServerLevel level)) return List.of();
        var manager = UnifiedDiskManager.existing(level.getServer());
        return manager == null ? List.of() : manager.mounts().disksForNetwork(network);
    }

    /** 当前服务器；仅在服务器线程取值，拿不到时返回 null。 */
    @Nullable
    public static MinecraftServer server() {
        return ServerLifecycleHooks.getCurrentServer();
    }
}
