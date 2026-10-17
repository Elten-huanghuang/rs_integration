package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.magic.BotaniaResonantManaPoolBlockEntity;
import com.huanghuang.rsintegration.magic.ResonantMagicBlockEntities;
import com.huanghuang.rsintegration.magic.ResonantMagicBlockMarker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import vazkii.botania.common.block.mana.ManaPoolBlock;
import vazkii.botania.common.block.block_entity.mana.ManaPoolBlockEntity;

/** 直接进入 Botania 原生魔力池类型链的共振池方块。 */
public final class BotaniaResonantManaPoolBlock extends ManaPoolBlock
        implements ResonantMagicBlockMarker {
    public BotaniaResonantManaPoolBlock(BlockBehaviour.Properties properties) {
        super(Variant.DEFAULT, properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BotaniaResonantManaPoolBlockEntity(
                ResonantMagicBlockEntities.MANA_POOL.get(), pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (type != ResonantMagicBlockEntities.MANA_POOL.get()) return null;
        return (tickLevel, pos, tickState, entity) -> {
            if (!(entity instanceof BotaniaResonantManaPoolBlockEntity pool)) return;
            if (tickLevel.isClientSide) {
                ManaPoolBlockEntity.clientTick(tickLevel, pos, tickState, pool);
            } else {
                ManaPoolBlockEntity.serverTick(tickLevel, pos, tickState, pool);
                pool.serverTick();
            }
        };
    }
}
