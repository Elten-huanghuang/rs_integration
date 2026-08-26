package com.huanghuang.rsintegration.resonance.bd;

import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.storage.bd.BeyondDimensionsReflection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/** Backend-neutral view over one UUID-owned BD resonance disk. */
public final class BDResonanceDiskView implements ResonanceStorageView {
    private final ServerPlayer player;
    private final UUID diskId;
    private final BDResonanceDiskData data;
    private final int networkId;

    public BDResonanceDiskView(ServerPlayer player, UUID diskId,
                               BDResonanceDiskData data, int networkId) {
        this.player = player;
        this.diskId = diskId;
        this.data = data;
        this.networkId = networkId;
    }

    @Override public String backendId() { return "beyonddimensions"; }

    @Override
    public List<StoredStack> storedStacks() {
        return available() ? data.snapshot(diskId) : List.of();
    }

    @Override public int getStored() { return available() ? data.stored(diskId) : 0; }
    @Override public int getCapacity() { return BDResonanceDiskData.CAPACITY; }

    @Override
    public int abilityMask() {
        BDResonanceDiskData.DiskRecord record = data.find(diskId);
        return available() && record != null ? record.abilityMask() : 0;
    }

    @Override public boolean hasAbility(int ability) { return (abilityMask() & ability) == ability; }

    @Override
    public boolean unlockAbility(int ability) {
        return available() && data.unlock(diskId, ability);
    }

    @Override
    public long contentRevision() { return data.revision(diskId); }

    @Override
    public SlotMutationResult reconcileSlotView(int slot, ItemStack previous, ItemStack requested) {
        return available() ? data.reconcile(diskId, slot, previous, requested)
                : SlotMutationResult.REJECTED;
    }

    @Override
    public ItemStack extractExactView(int slot, ItemStack template, int size, boolean simulate) {
        return available() ? data.extract(diskId, slot, template, size, simulate) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack insertView(int slot, ItemStack stack, int size, boolean simulate) {
        return available() ? data.insert(diskId, slot, stack, size, simulate) : stack;
    }

    private boolean available() {
        return player.isAlive() && BeyondDimensionsReflection.isAuthorizedNetwork(player, networkId);
    }
}
