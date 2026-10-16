package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskManager;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

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
    public static int total(@Nullable MinecraftServer server, MagicResourceKind kind) {
        return MagicDiskStore.total(disks(server), kind);
    }

    /** 该资源的可用量（库存扣除其他任务尚未使用的预留）。 */
    public static int available(@Nullable MinecraftServer server, MagicResourceKind kind) {
        return MagicDiskStore.available(disks(server), kind);
    }

    /** 在当前已挂载的盘上预留；返回的账本必须由调用方关闭。 */
    public static MagicResourceReservations.Pool reserve(@Nullable MinecraftServer server,
                                                         MagicResourceKind kind, int requested) {
        return MagicResourceReservations.reserve(UnifiedDiskManager.existing(server), kind, requested);
    }

    /** 充入库存，返回实际接收量。 */
    public static int insert(@Nullable MinecraftServer server, MagicResourceKind kind,
                             int requested, boolean perform) {
        return MagicDiskStore.insert(disks(server), kind, requested, perform);
    }

    /** 从库存取出，返回实际取出量；会把其他任务的预留留在盘上。 */
    public static int extract(@Nullable MinecraftServer server, MagicResourceKind kind,
                              int requested, boolean perform) {
        return MagicResourceReservations.extract(UnifiedDiskManager.existing(server), kind, requested, perform);
    }

    /** 当前服务器；仅在服务器线程取值，拿不到时返回 null。 */
    @Nullable
    public static MinecraftServer server() {
        return ServerLifecycleHooks.getCurrentServer();
    }
}
