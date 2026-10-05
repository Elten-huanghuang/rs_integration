package com.huanghuang.rsintegration.disk.core;

import com.huanghuang.rsintegration.util.ItemStackUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
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
    public boolean dirty() { checkThread(); return items.dirty() || fluids.dirty(); }
    public int payloadBytes() { return payloadBytes; }

    public int insert(FrozenKey key, int amount, boolean perform) {
        checkThread();
        if (!key.stored()) throw new IllegalArgumentException("查询身份不能作为库存模板");
        ResourceTable table = table(key.kind());
        int slot = table.exactSlot(key);
        boolean newKey = slot < 0;
        if (newKey && (key.payloadBytes() > limits.entryBytes || key.payloadBytes() > limits.payloadBytes - payloadBytes)) return 0;
        int accepted = table.insert(key, slot, amount, perform);
        if (perform && accepted > 0 && newKey) payloadBytes += key.payloadBytes();
        return accepted;
    }

    public int insertItem(ItemStack source, int amount, boolean perform) {
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
        return items.canCreate() ? insertNew(query, query.freezeItem(source), amount, perform) : 0;
    }

    public int insertFluid(FluidStack source, int amount, boolean perform) {
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
        return fluids.canCreate() ? insertNew(query, query.freezeFluid(source), amount, perform) : 0;
    }

    private int insertNew(FrozenKey query, FrozenKey key, int amount, boolean perform) {
        // 模组能力若在复制/归一化时改变身份，回到完整查找；常规未命中不再重复查表。
        if (!query.equals(key)) return insert(key, amount, perform);
        if (key.payloadBytes() > limits.entryBytes || key.payloadBytes() > limits.payloadBytes - payloadBytes) return 0;
        int accepted = table(key.kind()).insert(key, -1, amount, perform);
        if (perform && accepted > 0) payloadBytes += key.payloadBytes();
        return accepted;
    }

    public int extract(FrozenKey.Kind kind, int slot, int amount, boolean perform) {
        checkThread();
        if (slot < 0) return 0;
        ResourceTable table = table(kind);
        FrozenKey key = table.key(slot);
        int taken = table.extract(slot, amount, perform);
        if (perform && taken > 0 && table.key(slot) == null) payloadBytes -= key.payloadBytes();
        return taken;
    }

    public void restorePayloadSize(int bytes) {
        if (bytes < 0 || bytes > limits.payloadBytes) throw new IllegalArgumentException("统一盘载荷总量非法");
        payloadBytes = bytes;
    }
}
