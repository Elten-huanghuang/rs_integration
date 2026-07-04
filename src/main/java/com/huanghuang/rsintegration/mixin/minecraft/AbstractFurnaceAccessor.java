package com.huanghuang.rsintegration.mixin.minecraft;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceAccessor {

    @Invoker("getBurnDuration")
    int rsi$callGetBurnDuration(ItemStack stack);
}
