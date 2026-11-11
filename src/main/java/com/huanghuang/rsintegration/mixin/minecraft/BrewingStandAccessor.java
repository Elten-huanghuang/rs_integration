package com.huanghuang.rsintegration.mixin.minecraft;

import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BrewingStandBlockEntity.class)
public interface BrewingStandAccessor {
    @Accessor("brewTime")
    int rsi$getBrewTime();
}
