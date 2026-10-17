package com.huanghuang.rsintegration.mods.arsnouveau;

import com.hollingsworth.arsnouveau.common.block.SourceJar;
import com.huanghuang.rsintegration.magic.ArsResonantSourceTankBlockEntity;
import com.huanghuang.rsintegration.magic.ResonantMagicBlockEntities;
import com.huanghuang.rsintegration.magic.ResonantMagicBlockMarker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/** 直接进入 Ars Nouveau 原生魔源罐类型链的共振储罐。 */
public final class ArsResonantSourceTankBlock extends SourceJar
        implements ResonantMagicBlockMarker {
    public ArsResonantSourceTankBlock(BlockBehaviour.Properties properties) {
        super(properties, "resonant_source_tank");
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ArsResonantSourceTankBlockEntity(
                ResonantMagicBlockEntities.SOURCE_TANK.get(), pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != ResonantMagicBlockEntities.SOURCE_TANK.get()) return null;
        return (tickLevel, pos, tickState, entity) -> {
            if (entity instanceof ArsResonantSourceTankBlockEntity tank) tank.serverTick();
        };
    }
}
