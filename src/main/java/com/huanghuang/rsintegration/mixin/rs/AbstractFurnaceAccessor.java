package com.huanghuang.rsintegration.mixin.rs;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor for {@link AbstractFurnaceBlockEntity#getBurnDuration(ItemStack)}.
 * <p>
 * Using Mixin {@code @Invoker} instead of reflection with SRG names
 * ({@code m_7743_}) ensures the method is correctly resolved in both
 * MojMap dev environments and SRG production environments.</p>
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceAccessor {

    @Invoker("getBurnDuration")
    int rsi$callGetBurnDuration(ItemStack stack);
}
