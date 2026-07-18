package com.huanghuang.rsintegration.mods.distantworlds;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

public record LithumAltarStatusSnapshot(ResourceLocation dimension, BlockPos pos,
                                        String currentRecipe, double currentEnergy,
                                        double maxEnergy, double recovery) {
    public static LithumAltarStatusSnapshot from(ResourceLocation dimension, BlockPos pos,
                                                  LithumAltarStateReader.Snapshot state) {
        return new LithumAltarStatusSnapshot(dimension, pos.immutable(), state.currentRecipe(),
                state.currentEnergy(), state.maxEnergy(), state.recovery());
    }
}
