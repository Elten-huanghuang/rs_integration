package com.huanghuang.rsintegration.resonance.bd;

import com.huanghuang.rsintegration.resonance.api.ResonanceStackRules;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** UUID-owned persistence for BD resonance disks; the BD network is only an access boundary. */
public final class BDResonanceDiskData extends SavedData {
    public static final String NAME = "rsi_bd_resonance_disks";
    public static final int SLOTS = 36;
    public static final int CAPACITY = SLOTS * 64;

    private final List<DiskRecord> disks = new ArrayList<>();

    private BDResonanceDiskData() {}

    public static BDResonanceDiskData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                BDResonanceDiskData::load, BDResonanceDiskData::new, NAME);
    }

    public synchronized DiskRecord getOrCreate(UUID diskId) {
        for (DiskRecord disk : disks) if (disk.diskId.equals(diskId)) return disk;
        DiskRecord created = new DiskRecord(diskId);
        disks.add(created);
        setDirty();
        return created;
    }

    public synchronized DiskRecord find(UUID diskId) {
        for (DiskRecord disk : disks) if (disk.diskId.equals(diskId)) return disk;
        return null;
    }

    /** Returns one other disk already bound to the network, if any. */
    public synchronized UUID findDiskIdByNetwork(int networkId, UUID exclude) {
        for (DiskRecord disk : disks) {
            if (disk.boundNetId == networkId && !Objects.equals(disk.diskId, exclude)) {
                return disk.diskId;
            }
        }
        return null;
    }

    /**
     * Atomically folds one disk into another. Every source stack must fit in an
     * exact-NBT-compatible target slot; otherwise nothing is changed.
     */
    public synchronized boolean mergeInto(UUID sourceId, UUID targetId) {
        DiskRecord source = find(sourceId);
        DiskRecord target = find(targetId);
        if (source == null || target == null || source == target) return true;

        ItemStack[] merged = new ItemStack[SLOTS];
        for (int i = 0; i < SLOTS; i++) merged[i] = target.slots[i].copy();
        for (ItemStack sourceStack : source.slots) {
            if (sourceStack.isEmpty()) continue;
            int remaining = sourceStack.getCount();
            for (int slot = 0; slot < SLOTS && remaining > 0; slot++) {
                ItemStack existing = merged[slot];
                if (existing.isEmpty() || !ResonanceStackRules.isSameVariant(existing, sourceStack)) continue;
                int limit = ResonanceStackRules.isLogicallyNonStackable(sourceStack)
                        ? 1 : Math.min(sourceStack.getMaxStackSize(), 64);
                int room = Math.max(0, limit - existing.getCount());
                int moved = Math.min(room, remaining);
                if (moved > 0) {
                    existing.grow(moved);
                    remaining -= moved;
                }
            }
            if (remaining > 0) {
                for (int slot = 0; slot < SLOTS && remaining > 0; slot++) {
                    if (!merged[slot].isEmpty()) continue;
                    int moved = Math.min(remaining,
                            ResonanceStackRules.isLogicallyNonStackable(sourceStack)
                                    ? 1 : Math.min(sourceStack.getMaxStackSize(), 64));
                    merged[slot] = sourceStack.copyWithCount(moved);
                    remaining -= moved;
                }
            }
            if (remaining > 0) return false;
        }

        for (int i = 0; i < SLOTS; i++) target.slots[i] = merged[i];
        for (int i = 0; i < SLOTS; i++) source.slots[i] = ItemStack.EMPTY;
        target.abilityMask |= source.abilityMask;
        source.boundNetId = -1;
        source.revision++;
        target.revision++;
        setDirty();
        return true;
    }

    public synchronized boolean bind(UUID diskId, UUID owner, int networkId) {
        DiskRecord disk = getOrCreate(diskId);
        if (disk.boundNetId == networkId && java.util.Objects.equals(disk.owner, owner)) return false;
        disk.bind(owner, networkId);
        disk.revision++;
        setDirty();
        return true;
    }

    public synchronized List<ResonanceStorageView.StoredStack> snapshot(UUID diskId) {
        DiskRecord disk = find(diskId);
        if (disk == null) return List.of();
        List<ResonanceStorageView.StoredStack> result = new ArrayList<>();
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!disk.slots[slot].isEmpty()) {
                result.add(new ResonanceStorageView.StoredStack(slot, disk.slots[slot]));
            }
        }
        return List.copyOf(result);
    }

    public synchronized int stored(UUID diskId) {
        DiskRecord disk = find(diskId);
        return storedCount(disk);
    }

    private static int storedCount(DiskRecord disk) {
        if (disk == null) return 0;
        int total = 0;
        for (ItemStack stack : disk.slots) total += stack.getCount();
        return total;
    }

    public synchronized long revision(UUID diskId) {
        DiskRecord disk = find(diskId);
        return disk == null ? 0L : disk.revision;
    }

    public synchronized ResonanceStorageView.SlotMutationResult reconcile(
            UUID diskId, int slot, ItemStack previous, ItemStack requested) {
        DiskRecord disk = getOrCreate(diskId);
        if (slot < 0 || slot >= SLOTS) return ResonanceStorageView.SlotMutationResult.REJECTED;
        ItemStack oldStack = clean(previous);
        ItemStack newStack = clean(requested);
        if (same(oldStack, newStack)) return ResonanceStorageView.SlotMutationResult.SUCCESS;
        int limit = ResonanceStackRules.isLogicallyNonStackable(newStack)
                ? 1 : Math.min(newStack.getMaxStackSize(), 64);
        if (!newStack.isEmpty() && newStack.getCount() > limit) {
            return ResonanceStorageView.SlotMutationResult.REJECTED;
        }
        int totalWithoutOld = storedCount(disk) - disk.slots[slot].getCount();
        if (totalWithoutOld + newStack.getCount() > CAPACITY) {
            return ResonanceStorageView.SlotMutationResult.REJECTED;
        }
        if (!disk.slots[slot].isEmpty()
                && !ResonanceStackRules.isSameVariant(disk.slots[slot], oldStack)) {
            return ResonanceStorageView.SlotMutationResult.REJECTED;
        }
        disk.slots[slot] = newStack.copy();
        disk.revision++;
        setDirty();
        return ResonanceStorageView.SlotMutationResult.SUCCESS;
    }

    public synchronized ItemStack extract(UUID diskId, int slot, ItemStack template,
                                           int amount, boolean simulate) {
        DiskRecord disk = find(diskId);
        if (disk == null || slot < 0 || slot >= SLOTS || amount <= 0) return ItemStack.EMPTY;
        ItemStack stored = disk.slots[slot];
        if (!ResonanceStackRules.isSameVariant(stored, clean(template))) return ItemStack.EMPTY;
        int take = Math.min(amount, stored.getCount());
        ItemStack result = stored.copyWithCount(take);
        if (!simulate) {
            stored.shrink(take);
            disk.revision++;
            setDirty();
        }
        return result;
    }

    public synchronized ItemStack insert(UUID diskId, int slot, ItemStack input,
                                         int amount, boolean simulate) {
        if (input.isEmpty() || amount <= 0) return input;
        DiskRecord disk = getOrCreate(diskId);
        int target = slot;
        if (target < 0) {
            target = firstCompatibleSlot(disk, input);
            if (target < 0) return input.copyWithCount(amount);
        }
        if (target >= SLOTS) return input.copyWithCount(amount);
        ItemStack current = disk.slots[target];
        if (!current.isEmpty() && !ResonanceStackRules.isSameVariant(current, input)) {
            return input.copyWithCount(amount);
        }
        int limit = ResonanceStackRules.isLogicallyNonStackable(input)
                ? 1 : Math.min(input.getMaxStackSize(), 64);
        int room = Math.min(limit - current.getCount(), CAPACITY - storedCount(disk));
        int accepted = Math.max(0, Math.min(amount, room));
        if (accepted <= 0) return input.copyWithCount(amount);
        if (!simulate) {
            if (current.isEmpty()) disk.slots[target] = input.copyWithCount(accepted);
            else current.grow(accepted);
            disk.revision++;
            setDirty();
        }
        return accepted == amount ? ItemStack.EMPTY : input.copyWithCount(amount - accepted);
    }

    public synchronized boolean unlock(UUID diskId, int ability) {
        DiskRecord disk = getOrCreate(diskId);
        int updated = disk.abilityMask | ability;
        if (updated == disk.abilityMask) return false;
        disk.abilityMask = updated;
        disk.revision++;
        setDirty();
        return true;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag) {
        ListTag encoded = new ListTag();
        for (DiskRecord disk : disks) {
            CompoundTag value = new CompoundTag();
            value.putUUID("id", disk.diskId);
            if (disk.owner != null) value.putUUID("owner", disk.owner);
            value.putInt("netId", disk.boundNetId);
            value.putInt("abilities", disk.abilityMask);
            value.putLong("revision", disk.revision);
            ListTag slots = new ListTag();
            for (int slot = 0; slot < SLOTS; slot++) {
                if (disk.slots[slot].isEmpty()) continue;
                CompoundTag entry = new CompoundTag();
                entry.putInt("slot", slot);
                entry.put("stack", disk.slots[slot].save(new CompoundTag()));
                slots.add(entry);
            }
            value.put("slots", slots);
            encoded.add(value);
        }
        tag.put("disks", encoded);
        return tag;
    }

    private static BDResonanceDiskData load(CompoundTag tag) {
        BDResonanceDiskData data = new BDResonanceDiskData();
        ListTag encoded = tag.getList("disks", Tag.TAG_COMPOUND);
        for (int i = 0; i < encoded.size(); i++) {
            CompoundTag value = encoded.getCompound(i);
            if (!value.hasUUID("id")) continue;
            DiskRecord disk = new DiskRecord(value.getUUID("id"));
            if (value.hasUUID("owner")) disk.owner = value.getUUID("owner");
            disk.boundNetId = value.getInt("netId");
            disk.abilityMask = value.getInt("abilities");
            disk.revision = Math.max(0L, value.getLong("revision"));
            ListTag slots = value.getList("slots", Tag.TAG_COMPOUND);
            for (int j = 0; j < slots.size(); j++) {
                CompoundTag entry = slots.getCompound(j);
                int slot = entry.getInt("slot");
                if (slot < 0 || slot >= SLOTS || !entry.contains("stack", Tag.TAG_COMPOUND)) continue;
                ItemStack stack = ItemStack.of(entry.getCompound("stack"));
                if (!stack.isEmpty()) disk.slots[slot] = stack;
            }
            data.disks.add(disk);
        }
        return data;
    }

    private static int firstCompatibleSlot(DiskRecord disk, ItemStack input) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (disk.slots[slot].isEmpty()
                    || ResonanceStackRules.isSameVariant(disk.slots[slot], input)) return slot;
        }
        return -1;
    }

    private static ItemStack clean(ItemStack stack) {
        return stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }

    private static boolean same(ItemStack first, ItemStack second) {
        return (first.isEmpty() && second.isEmpty())
                || (!first.isEmpty() && !second.isEmpty()
                && first.getCount() == second.getCount()
                && ResonanceStackRules.isSameVariant(first, second));
    }

    public static final class DiskRecord {
        private final UUID diskId;
        private UUID owner;
        private int boundNetId = -1;
        private int abilityMask;
        private long revision;
        private final ItemStack[] slots = new ItemStack[SLOTS];

        private DiskRecord(UUID diskId) {
            this.diskId = diskId;
            for (int i = 0; i < SLOTS; i++) slots[i] = ItemStack.EMPTY;
        }

        public UUID diskId() { return diskId; }
        public UUID owner() { return owner; }
        public int boundNetId() { return boundNetId; }
        public int abilityMask() { return abilityMask; }
        public void bind(UUID owner, int netId) { this.owner = owner; this.boundNetId = netId; }
    }
}
