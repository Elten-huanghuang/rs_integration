package com.huanghuang.rsintegration.mixin.minecraft;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceAccessor {

    @Accessor("cookingProgress") int rsi$getCookingProgress();
    @Accessor("cookingTotalTime") int rsi$getCookingTotalTime();

    @Invoker("getBurnDuration")
    int rsi$callGetBurnDuration(ItemStack stack);
}
