package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.storage.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.storage.disk.rs.UnifiedDiskManager;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;

import javax.annotation.Nullable;

/**
 * 归墟盘魔法资源的内存预留账本。
 *
 * <p>预留只降低其他使用者看到的可用量，不立即减少真实库存；真正从盘里提取时
 * 才扣预留。预留按盘 ID 和资源种类隔离，多个并行任务不会重复使用同一份库存。
 * 预留在服务器主线程读写，盘被拔出时对应条目随预留关闭一起释放。</p>
 */
public final class MagicResourceReservations {
    static final Object LOCK = new Object();
    private static final Map<UUID, long[]> BY_DISK = new HashMap<>();

    private MagicResourceReservations() {}

    /** 该盘上已被其他任务预留的数量。 */
    static long reserved(UUID diskId, MagicResourceKind kind) {
        long[] amounts = BY_DISK.get(diskId);
        return amounts == null ? 0 : amounts[kind.ordinal()];
    }

    private static void add(UUID diskId, MagicResourceKind kind, long delta) {
        if (delta == 0) return;
        long[] amounts = BY_DISK.computeIfAbsent(diskId,
                ignored -> new long[MagicResourceKind.values().length]);
        amounts[kind.ordinal()] = SaturatingLongMath.signedAdd(amounts[kind.ordinal()], delta);
        if (amounts[kind.ordinal()] < 0) amounts[kind.ordinal()] = 0;
        // 预留的增减同样改变终端显示的可用量，所以也算一次库存变动。
        MagicResourceDirty.bump();
    }

    /** 在当前已挂载的盘上预留，返回可继续提取的凭据。 */
    public static Pool reserve(UnifiedDiskManager manager, MagicResourceKind kind, long requested) {
        return reserve(manager == null ? List.of() : MagicDiskStore.mountedDisks(manager),
                kind, requested);
    }

    /** 在给定网络磁盘集合上预留，返回可继续提取的凭据。 */
    public static Pool reserve(List<UnifiedDiskCore> disks, MagicResourceKind kind,
                               long requested) {
        Pool pool = new Pool(kind);
        if (disks == null || disks.isEmpty() || requested <= 0) return pool;
        synchronized (LOCK) {
            long left = Math.min(requested, MagicDiskStore.capacity(disks, kind));
            for (UnifiedDiskCore disk : disks) {
                if (left <= 0) break;
                long available = Math.max(0, disk.magicAmount(kind) - reserved(disk.diskId, kind));
                long take = Math.min(available, left);
                if (take <= 0) continue;
                pool.claims.merge(disk, take, SaturatingLongMath::add);
                add(disk.diskId, kind, take);
                pool.remaining += take;
                left -= take;
            }
        }
        return pool;
    }

    /** 从盘上取出没有归属的余额；其他任务已经预留的部分不会被挪用。 */
    public static long extract(@Nullable UnifiedDiskManager manager, MagicResourceKind kind,
                               long requested, boolean perform) {
        if (manager == null || requested <= 0) return 0;
        long moved = 0;
        synchronized (LOCK) {
            for (UnifiedDiskCore disk : MagicDiskStore.mountedDisks(manager)) {
                if (moved >= requested) break;
                long want = requested - moved;
                long free = Math.max(0, disk.magicAmount(kind) - reserved(disk.diskId, kind));
                long fromFree = Math.min(want, free);
                if (fromFree > 0) {
                    long taken = disk.extractMagic(kind, fromFree, perform);
                    moved += taken;
                }
            }
        }
        return moved;
    }

    /** 从指定网络的归墟盘提取，不触碰其他网络的预留。 */
    public static long extract(List<UnifiedDiskCore> disks, MagicResourceKind kind,
                               long requested, boolean perform) {
        if (disks == null || requested <= 0) return 0;
        long moved = 0;
        synchronized (LOCK) {
            for (UnifiedDiskCore disk : disks) {
                if (moved >= requested) break;
                long free = Math.max(0, disk.magicAmount(kind) - reserved(disk.diskId, kind));
                long take = Math.min(requested - moved, free);
                if (take > 0) moved += disk.extractMagic(kind, take, perform);
            }
        }
        if (perform && moved > 0) MagicResourceDirty.bump();
        return moved;
    }

    /** 一次任务持有的预留；所有方法都在服务器主线程调用。 */
    public static final class Pool implements MagicResourceNetworkPool {
        private final MagicResourceKind kind;
        private final Map<UnifiedDiskCore, Long> claims = new LinkedHashMap<>();
        private long remaining;

        private Pool(MagicResourceKind kind) {
            this.kind = kind;
        }

        public MagicResourceKind kind() {
            return kind;
        }

        @Override
        public long remaining() {
            return remaining;
        }

        /** 从本次预留中提取；提取多少就减少多少预留，提取失败不改变状态。 */
        @Override
        public long withdraw(long requested) {
            if (requested <= 0 || remaining <= 0) return 0;
            long moved = 0;
            synchronized (LOCK) {
                for (Map.Entry<UnifiedDiskCore, Long> entry : claims.entrySet()) {
                    if (moved >= requested) break;
                    long claim = Math.max(0, entry.getValue());
                    long want = Math.min(claim, requested - moved);
                    if (want <= 0) continue;
                    long extracted = entry.getKey().extractMagic(kind, want, true);
                    if (extracted <= 0) continue;
                    entry.setValue(claim - extracted);
                    add(entry.getKey().diskId, kind, -extracted);
                    remaining -= extracted;
                    moved += extracted;
                }
            }
            return moved;
        }

        /** 把未接受的部分放回预留，仍然计入本任务的账。 */
        @Override
        public void refund(long amount) {
            if (amount <= 0) return;
            synchronized (LOCK) {
                for (UnifiedDiskCore disk : claims.keySet()) {
                    long accepted = disk.insertMagic(kind, amount, true);
                    if (accepted <= 0) continue;
                    claims.merge(disk, accepted, SaturatingLongMath::add);
                    add(disk.diskId, kind, accepted);
                    remaining += accepted;
                    return;
                }
            }
        }

        @Override
        public void close() {
            synchronized (LOCK) {
                for (Map.Entry<UnifiedDiskCore, Long> entry : claims.entrySet()) {
                    add(entry.getKey().diskId, kind, -entry.getValue());
                }
                claims.clear();
                remaining = 0;
            }
        }

        /** 测试与诊断用：当前声明的盘数量。 */
        int claimCount() {
            return claims.size();
        }
    }
}
