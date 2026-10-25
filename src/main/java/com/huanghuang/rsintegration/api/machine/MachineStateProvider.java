package com.huanghuang.rsintegration.api.machine;

import net.minecraft.world.level.block.entity.BlockEntity;

public interface MachineStateProvider {
    MachineState readState(MachineContext context, BlockEntity blockEntity);
}
