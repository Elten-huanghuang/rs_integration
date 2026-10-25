package com.huanghuang.rsintegration.api.machine;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;

public interface MachineIntegration {
    ResourceLocation id();

    boolean supports(BlockEntity blockEntity);

    MachineDescriptorResult describe(
            MachineContext context,
            BlockEntity blockEntity
    );

    default Optional<MachineStateProvider> stateProvider() {
        return Optional.empty();
    }
}
