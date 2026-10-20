package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;

/** 绑定、展示快照与 RS 读写逻辑的共同实现。 */
final class ResonantMagicStorageData {
    static final String BINDING = "RSBinding";

    private final BlockEntity owner;
    private final MagicResourceKind kind;
    private final int capacity;
    private CompoundTag binding;
    private int displayAmount;
    private int displayCapacity;
    private boolean displayReachable;

    ResonantMagicStorageData(BlockEntity owner, MagicResourceKind kind, int capacity) {
        this.owner = owner;
        this.kind = kind;
        this.capacity = capacity;
        this.displayCapacity = capacity;
    }

    MagicResourceKind kind() {
        return kind;
    }

    int resourceCapacity() {
        if (owner.getLevel() != null && !owner.getLevel().isClientSide && binding != null) {
            INetwork network = resolveNetwork();
            if (network != null) {
                return SaturatingLongMath.clampToInt(MagicResourceEndpoint.capacity(network, kind));
            }
        }
        return displayCapacity;
    }

    boolean isBound() {
        return binding != null;
    }

    @Nullable
    CompoundTag binding() {
        return binding == null ? null : binding.copy();
    }

    void setBinding(@Nullable CompoundTag next) {
        binding = next == null ? null : next.copy();
        displayReachable = binding != null && resolveNetwork() != null;
        displayAmount = binding == null ? 0 : currentAmount();
        displayCapacity = binding == null ? capacity : resourceCapacity();
        owner.setChanged();
        syncToClient();
    }

    void clearBinding() {
        binding = null;
        displayAmount = 0;
        displayCapacity = capacity;
        displayReachable = false;
        owner.setChanged();
        syncToClient();
    }

    boolean isBoundTo(@Nullable CompoundTag next) {
        return binding != null && next != null && binding.equals(next);
    }

    int currentAmount() {
        if (owner.getLevel() == null || owner.getLevel().isClientSide || binding == null) {
            return displayAmount;
        }
        INetwork network = resolveNetwork();
        return network == null ? 0
                : SaturatingLongMath.clampToInt(MagicResourceEndpoint.total(network, kind));
    }

    boolean reachable() {
        if (owner.getLevel() == null || owner.getLevel().isClientSide) return displayReachable;
        return resolveNetwork() != null;
    }

    int insert(int amount) {
        if (amount <= 0 || owner.getLevel() == null || owner.getLevel().isClientSide) return 0;
        INetwork network = resolveNetwork();
        if (network == null) return 0;
        int accepted = SaturatingLongMath.clampToInt(
                MagicResourceEndpoint.insert(network, kind, amount, true));
        syncSnapshot();
        return accepted;
    }

    int extract(int amount) {
        if (amount <= 0 || owner.getLevel() == null || owner.getLevel().isClientSide) return 0;
        INetwork network = resolveNetwork();
        if (network == null) return 0;
        int extracted = SaturatingLongMath.clampToInt(
                MagicResourceEndpoint.extract(network, kind, amount, true));
        syncSnapshot();
        return extracted;
    }

    void serverTick() {
        if (owner.getLevel() == null || owner.getLevel().isClientSide
                || owner.getLevel().getGameTime() % 10 != 0) {
            return;
        }
        syncSnapshot();
    }

    void save(CompoundTag tag) {
        tag.putString("Kind", kind.id());
        tag.putInt("Capacity", displayCapacity);
        tag.putInt("DisplayAmount", displayAmount);
        tag.putBoolean("DisplayReachable", displayReachable);
        if (binding != null) tag.put(BINDING, binding.copy());
    }

    void load(CompoundTag tag) {
        binding = tag.contains(BINDING) ? tag.getCompound(BINDING).copy() : null;
        displayAmount = Math.max(0, tag.getInt("DisplayAmount"));
        displayCapacity = tag.contains("Capacity")
                ? Math.max(0, tag.getInt("Capacity")) : capacity;
        displayReachable = tag.getBoolean("DisplayReachable");
    }

    @Nullable
    private INetwork resolveNetwork() {
        if (owner.getLevel() == null || binding == null) return null;
        return RSIntegrationNetwork.resolveNetworkForBindingData(
                owner.getLevel().getServer(), binding);
    }

    private void syncSnapshot() {
        int amount = currentAmount();
        boolean reachable = resolveNetwork() != null;
        int networkCapacity = reachable ? resourceCapacity() : displayCapacity;
        if (displayAmount == amount && displayCapacity == networkCapacity
                && displayReachable == reachable) {
            return;
        }
        displayAmount = amount;
        displayCapacity = networkCapacity;
        displayReachable = reachable;
        owner.setChanged();
        syncToClient();
    }

    private void syncToClient() {
        if (owner.getLevel() != null && !owner.getLevel().isClientSide) {
            owner.getLevel().sendBlockUpdated(owner.getBlockPos(),
                    owner.getBlockState(), owner.getBlockState(), 3);
        }
    }
}
