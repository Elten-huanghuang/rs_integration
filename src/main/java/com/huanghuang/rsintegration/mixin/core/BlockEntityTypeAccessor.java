package com.huanghuang.rsintegration.mixin.core;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 允许原生子类在保留自定义注册 ID 的同时继承 Botania 方块实体。
 *
 * <p>Minecraft 的方块实体构造器会固定类型字段。这里只修正类型标识，不改变
 * Botania 对实体类型的运行时判定。</p>
 */
@Mixin(BlockEntity.class)
public interface BlockEntityTypeAccessor {
    @Mutable
    @Accessor("type")
    void rsi$setType(BlockEntityType<?> type);
}
