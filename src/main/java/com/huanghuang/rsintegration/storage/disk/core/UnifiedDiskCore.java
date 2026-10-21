package com.huanghuang.rsintegration.storage.disk.core;

import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.util.ItemStackUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import java.util.Arrays;
import java.util.UUID;

public final class UnifiedDiskCore {
    public record Limits(int items, int fluids, int entryBytes, int payloadBytes) {
        public Limits expandEntries(int itemCapacity, int fluidCapacity) {
            return new Limits(Math.max(items, itemCapacity), Math.max(fluids, fluidCapacity),
                    entryBytes, payloadBytes);
        }

        public Limits {
            if (items < 1 || items > 262144 || fluids < 1 || fluids > 262144
                    || entryBytes < 1024 || entryBytes > FrozenKey.MAX_BYTES
                    || payloadBytes < 1048576 || payloadBytes > 536870912) {
                throw new IllegalArgumentException("统一盘容量描述非法");
            }
        }
    }
    private final Thread ownerThread = Thread.currentThread();
    public final UUID worldId, diskId, owner;
    public final Limits limits;
    public final ResourceTable items, fluids;
    private int payloadBytes;
    private final long[] magicAmounts = new long[MagicResourceKind.values().length];
    private final long[] savedMagicAmounts = new long[MagicResourceKind.values().length];

    public UnifiedDiskCore(UUID worldId, UUID diskId, UUID owner, Limits limits) {
        this.worldId = worldId;
        this.diskId = diskId;
        this.owner = owner;
        this.limits = limits;
        long epoch = UUID.randomUUID().getMostSignificantBits();
        items = new ResourceTable(limits.items, epoch);
        fluids = new ResourceTable(limits.fluids, epoch);
    }

    public void checkThread() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("统一盘库存仅允许拥有线程访问");
    }

    public ResourceTable table(FrozenKey.Kind kind) { return kind == FrozenKey.Kind.ITEM ? items : fluids; }
    public boolean dirty() { checkThread(); return items.dirty() || fluids.dirty() || !Arrays.equals(magicAmounts, savedMagicAmounts); }
    public int payloadBytes() { return payloadBytes; }

    /** 该魔法资源的当前库存；与物品、流体分开计量。 */
    public long magicAmount(MagicResourceKind kind) {
        checkThread();
        return magicAmounts[kind.ordinal()];
    }

    /** 单条目上界由资源自身决定；原生接口只在各自的边界显式分段。 */
    public long magicCapacity(MagicResourceKind kind) {
        checkThread();
        return kind.capacity();
    }

    public long insertMagic(MagicResourceKind kind, long amount, boolean perform) {
        checkThread();
        if (amount <= 0) return 0;
        int index = kind.ordinal();
        long accepted = Math.min(amount, magicCapacity(kind) - magicAmounts[index]);
        if (accepted <= 0) return 0;
        if (perform) magicAmounts[index] += accepted;
        return accepted;
    }

    public long extractMagic(MagicResourceKind kind, long amount, boolean perform) {
        checkThread();
        if (amount <= 0) return 0;
        int index = kind.ordinal();
        long taken = Math.min(amount, magicAmounts[index]);
        if (perform) magicAmounts[index] -= taken;
        return taken;
    }

    /** 从存档恢复；长度不符或为负都视为损坏，由调用方拒绝加载。 */
    public void restoreMagic(long[] amounts) {
        if (amounts == null || amounts.length != magicAmounts.length) {
            throw new IllegalArgumentException("统一盘魔法资源记录长度非法");
        }
        for (int index = 0; index < amounts.length; index++) {
            if (amounts[index] < 0) throw new IllegalArgumentException("统一盘魔法资源数量非法");
            magicAmounts[index] = amounts[index];
            savedMagicAmounts[index] = amounts[index];
        }
    }

    public long[] magicSnapshot() {
        checkThread();
        return magicAmounts.clone();
    }

    public void acknowledgeMagic(long[] amounts) {
        checkThread();
        if (amounts == null || amounts.length != savedMagicAmounts.length) return;
        System.arraycopy(amounts, 0, savedMagicAmounts, 0, amounts.length);
    }

    public long goetySouls() { return magicAmount(MagicResourceKind.SOUL); }

    public long insertGoetySouls(long amount, boolean perform) {
        return insertMagic(MagicResourceKind.SOUL, amount, perform);
    }

    public long extractGoetySouls(long amount, boolean perform) {
        return extractMagic(MagicResourceKind.SOUL, amount, perform);
    }

    public void restoreGoetySouls(long amount) {
        if (amount < 0) throw new IllegalArgumentException("统一盘灵魂数量非法");
        magicAmounts[MagicResourceKind.SOUL.ordinal()] = amount;
        savedMagicAmounts[MagicResourceKind.SOUL.ordinal()] = amount;
    }

    public void acknowledgeGoetySouls(long amount) {
        checkThread();
        if (amount == magicAmounts[MagicResourceKind.SOUL.ordinal()]) {
            savedMagicAmounts[MagicResourceKind.SOUL.ordinal()] = amount;
        }
    }

    public long insert(FrozenKey key, long amount, boolean perform) {
        checkThread();
        if (!key.stored()) throw new IllegalArgumentException("查询身份不能作为库存模板");
        ResourceTable table = table(key.kind());
        int slot = table.exactSlot(key);
        boolean newKey = slot < 0;
        if (newKey && (key.payloadBytes() > limits.entryBytes || key.payloadBytes() > limits.payloadBytes - payloadBytes)) return 0;
        long accepted = table.insert(key, slot, amount, perform);
        if (perform && accepted > 0 && newKey) payloadBytes += key.payloadBytes();
        return accepted;
    }

    public long insertItem(ItemStack source, long amount, boolean perform) {
        checkThread();
        if (amount <= 0 || source.isEmpty()) return 0;
        source = ItemStackUtils.normalizeEmptyTag(source);
        if (FrozenKey.plainItem(source)) {
            int slot = items.plainSlot(source.getItem());
            return slot >= 0 ? items.insertExisting(slot, amount, perform)
                    : items.canCreate() ? insert(FrozenKey.item(source), amount, perform) : 0;
        }
        FrozenKey query = FrozenKey.queryItem(source);
        int slot = items.exactSlot(query);
        if (slot >= 0) return items.insertExisting(slot, amount, perform);
        return insert(query.freezeItem(source), amount, perform);
    }

    public long insertFluid(FluidStack source, long amount, boolean perform) {
        checkThread();
        if (amount <= 0 || source.isEmpty()) return 0;
        if (source.getTag() == null) {
            int slot = fluids.plainSlot(source.getFluid());
            return slot >= 0 ? fluids.insertExisting(slot, amount, perform)
                    : fluids.canCreate() ? insert(FrozenKey.fluid(source), amount, perform) : 0;
        }
        FrozenKey query = FrozenKey.queryFluid(source);
        int slot = fluids.exactSlot(query);
        if (slot >= 0) return fluids.insertExisting(slot, amount, perform);
        return insert(query.freezeFluid(source), amount, perform);
    }

    public long extract(FrozenKey.Kind kind, int slot, long amount, boolean perform) {
        checkThread();
        if (slot < 0) return 0;
        ResourceTable table = table(kind);
        FrozenKey key = table.key(slot);
        long taken = table.extract(slot, amount, perform);
        if (perform && taken > 0 && table.key(slot) == null) payloadBytes -= key.payloadBytes();
        return taken;
    }

    public void restorePayloadSize(int bytes) {
        if (bytes < 0 || bytes > limits.payloadBytes) throw new IllegalArgumentException("统一盘载荷总量非法");
        payloadBytes = bytes;
    }
}
