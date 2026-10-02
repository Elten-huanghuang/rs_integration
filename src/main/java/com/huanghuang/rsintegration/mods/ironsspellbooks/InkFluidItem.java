package com.huanghuang.rsintegration.mods.ironsspellbooks;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/** 递归账本中的流体凭据；数量按 mB 计算，写回 RS 时还原为流体。 */
public final class InkFluidItem extends Item {
    public InkFluidItem() { super(new Properties()); }

    @Override
    public Component getName(ItemStack stack) {
        FluidStack fluid = InkFluidSupport.fluid(stack);
        return fluid.isEmpty() ? super.getName(stack) : fluid.getDisplayName();
    }
}
