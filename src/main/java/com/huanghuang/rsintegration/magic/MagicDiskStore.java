package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskManager;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 归墟盘上魔法资源的存取入口。
 *
 * <p>和物品、流体不同，魔法资源只在混合终端和合成执行时读写，不进入 RS 原版
 * 缓存，所以这里直接按盘操作，不额外维护一份网络库存副本。</p>
 */
public final class MagicDiskStore {
    private MagicDiskStore() {}

    /**
     * 当前已挂载的归墟盘，按盘 ID 排序保证遍历顺序稳定。
     *
     * <p>不按 RS 网络过滤是刻意的：魔法资源不在 RS 缓存里，没有网络级索引，
     * 只能以“管理器里已挂载的盘”为准；未挂载的盘不参与任何读写和预留。</p>
     */
    public static List<UnifiedDiskCore> mountedDisks(@Nullable UnifiedDiskManager manager) {
        if (manager == null) return List.of();
        List<UnifiedDiskCore> disks = new ArrayList<>();
        for (UnifiedDiskManager.Entry entry : manager.entries().values()) {
            if (entry.core != null && manager.mounts().mounted(entry.id)) {
                disks.add(entry.core);
            }
        }
        disks.sort(Comparator.comparing(disk -> disk.diskId));
        return disks;
    }

    /** 服务器上所有已挂载的归墟盘。 */
    public static List<UnifiedDiskCore> mountedDisks(@Nullable MinecraftServer server) {
        return mountedDisks(UnifiedDiskManager.existing(server));
    }

    /** 网络内该资源的当前库存；不含预留。 */
    public static int total(List<UnifiedDiskCore> disks, MagicResourceKind kind) {
        long total = 0L;
        for (UnifiedDiskCore disk : disks) {
            total += Math.max(0, disk.magicAmount(kind));
            if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    /** 已挂载归墟盘为该资源提供的总容量，按盘汇总并在 int 上限处饱和。 */
    public static int capacity(List<UnifiedDiskCore> disks, MagicResourceKind kind) {
        long capacity = 0L;
        for (UnifiedDiskCore disk : disks) {
            capacity += Math.max(0, disk.magicCapacity(kind));
            if (capacity >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) capacity;
    }

    /** 网络内该资源的可用量（库存减去其他任务尚未使用的预留）。 */
    public static int available(List<UnifiedDiskCore> disks, MagicResourceKind kind) {
        long available = 0L;
        for (UnifiedDiskCore disk : disks) {
            int reserved;
            synchronized (MagicResourceReservations.LOCK) {
                reserved = MagicResourceReservations.reserved(disk.diskId, kind);
            }
            available += Math.max(0, disk.magicAmount(kind) - reserved);
            if (available >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) available;
    }

    /** 按盘顺序提取，返回实际移出的数量。 */
    public static int extract(List<UnifiedDiskCore> disks, MagicResourceKind kind,
                              int requested, boolean perform) {
        if (requested <= 0) return 0;
        int moved = 0;
        for (UnifiedDiskCore disk : disks) {
            if (moved >= requested) break;
            int taken = disk.extractMagic(kind, requested - moved, perform);
            moved += taken;
        }
        if (perform && moved > 0) MagicResourceDirty.bump();
        return moved;
    }

    /** 按盘顺序插入，返回实际收下的数量。 */
    public static int insert(List<UnifiedDiskCore> disks, MagicResourceKind kind,
                             int requested, boolean perform) {
        if (requested <= 0) return 0;
        int moved = 0;
        for (UnifiedDiskCore disk : disks) {
            if (moved >= requested) break;
            int accepted = disk.insertMagic(kind, requested - moved, perform);
            moved += accepted;
        }
        if (perform && moved > 0) MagicResourceDirty.bump();
        return moved;
    }
}
