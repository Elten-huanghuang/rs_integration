package com.huanghuang.rsintegration.magic;

import com.hollingsworth.arsnouveau.api.source.ISourceTile;
import com.hollingsworth.arsnouveau.api.source.ISpecialSourceProvider;
import com.hollingsworth.arsnouveau.api.source.SourceManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Ars Nouveau 原生 Source 容器适配。 */
public final class ArsResonantSourceTankBlockEntity extends ResonantMagicBlockEntity
        implements ISourceTile {
    private final ISpecialSourceProvider specialSourceProvider = new ISpecialSourceProvider() {
        @Override public ISourceTile getSource() { return ArsResonantSourceTankBlockEntity.this; }
        @Override public BlockPos getCurrentPos() { return worldPosition; }
        @Override public boolean isValid() {
            return level != null && !level.isClientSide && !isRemoved()
                    && level.hasChunkAt(worldPosition)
                    && level.getBlockEntity(worldPosition) == ArsResonantSourceTankBlockEntity.this;
        }
    };

    public ArsResonantSourceTankBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, MagicResourceKind.SOURCE, MagicResourceKind.SOURCE.capacity());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            SourceManager.INSTANCE.addInterface(level, specialSourceProvider);
        }
    }

    /** Ars 的传输计算还会按来源余额和目标余量再限流，这里不人为截断。 */
    @Override public int getTransferRate() { return Integer.MAX_VALUE; }
    @Override public boolean canAcceptSource() { return isBound() && reachable() && getSource() < getMaxSource(); }
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
        if (amount > 0) insert(Math.min(amount, Math.max(0, getMaxSource() - getSource())));
        return getSource();
    }
    @Override public int removeSource(int amount) {
        if (amount > 0) extract(Math.min(amount, getSource()));
        return getSource();
    }
}
