package com.huanghuang.rsintegration.magic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** 保存 RS 绑定和客户端显示快照的公共方块实体。 */
public class ResonantMagicBlockEntity extends BlockEntity implements ResonantMagicStorage {
    private final ResonantMagicStorageData storage;

    public ResonantMagicBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                    MagicResourceKind kind, int capacity) {
        super(type, pos, state);
        storage = new ResonantMagicStorageData(this, kind, capacity);
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

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
