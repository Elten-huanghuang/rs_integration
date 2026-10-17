package com.huanghuang.rsintegration.magic;

import com.hollingsworth.arsnouveau.common.block.tile.SourceJarTile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Ars Nouveau 原生 Source 容器适配。 */
public final class ArsResonantSourceTankBlockEntity extends SourceJarTile
        implements ResonantMagicStorage {
    private final ResonantMagicStorageData storage;

    public ArsResonantSourceTankBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(castType(type), pos, state);
        storage = new ResonantMagicStorageData(this, MagicResourceKind.SOURCE,
                MagicResourceKind.SOURCE.capacity());
    }

    @Override public MagicResourceKind resourceKind() { return storage.kind(); }
    @Override public int resourceCapacity() { return storage.resourceCapacity(); }
    @Override public boolean isBound() { return storage.isBound(); }
    @Override public CompoundTag binding() { return storage.binding(); }
    @Override public void setBinding(CompoundTag next) { storage.setBinding(next); }
    @Override public void clearBinding() { storage.clearBinding(); }
    @Override public boolean isBoundTo(CompoundTag next) { return storage.isBoundTo(next); }
    @Override public int currentAmount() { return storage.currentAmount(); }
    @Override public boolean reachable() { return storage.reachable(); }
    @Override public int insert(int amount) { return storage.insert(amount); }
    @Override public int extract(int amount) { return storage.extract(amount); }
    @Override public void serverTick() { storage.serverTick(); }

    /** Ars 的传输计算还会按来源余额和目标余量再限流，这里不人为截断。 */
    @Override public int getTransferRate() { return Integer.MAX_VALUE; }
    /** 只有绑定且可达的网络才有实际库存，避免 Ars 机器输出后发生能量丢失。 */
    @Override public boolean canAcceptSource() {
        return isBound() && reachable() && getSource() < getMaxSource();
    }
    @Override public int getSource() { return currentAmount(); }
    @Override public int getMaxSource() { return resourceCapacity(); }
    @Override public void setMaxSource(int ignored) {}
    @Override public int setSource(int amount) {
        int current = getSource();
        int target = Math.max(0, Math.min(amount, getMaxSource()));
        if (target > current) insert(target - current);
        else if (target < current) extract(current - target);
        return getSource();
    }
    @Override public int addSource(int amount) {
        if (amount > 0) {
            insert(Math.min(amount, Math.max(0, getMaxSource() - getSource())));
        } else if (amount < 0) {
            removeSource((int) Math.min(-(long) amount, Integer.MAX_VALUE));
        }
        return getSource();
    }
    @Override public int removeSource(int amount) {
        if (amount > 0) {
            extract(Math.min(amount, getSource()));
        } else if (amount < 0) {
            addSource((int) Math.min(-(long) amount, Integer.MAX_VALUE));
        }
        return getSource();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        storage.save(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        storage.load(tag);
    }

    @SuppressWarnings("unchecked")
    private static BlockEntityType<? extends SourceJarTile> castType(BlockEntityType<?> type) {
        return (BlockEntityType<? extends SourceJarTile>) type;
    }
}
