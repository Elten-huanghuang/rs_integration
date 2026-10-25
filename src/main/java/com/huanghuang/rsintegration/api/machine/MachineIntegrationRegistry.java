package com.huanghuang.rsintegration.api.machine;

import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Optional;

public interface MachineIntegrationRegistry {
    List<MachineIntegration> findAll(BlockEntity blockEntity);

    MachineClaim claim(BlockEntity blockEntity);

    default Optional<MachineIntegration> winnerFor(BlockEntity blockEntity) {
        return Optional.ofNullable(claim(blockEntity).winner());
    }
}
