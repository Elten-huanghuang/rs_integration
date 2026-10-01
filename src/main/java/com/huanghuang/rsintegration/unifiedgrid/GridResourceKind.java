package com.huanghuang.rsintegration.unifiedgrid;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/** 类别只用于路由；物品个数与流体 mB 不互相换算。 */
public enum GridResourceKind {
    ITEM, FLUID;

    public static GridResourceKind fromId(int id) {
        if (id < 0 || id >= values().length) throw new IllegalArgumentException("无效资源类别");
        return values()[id];
    }

    public Object copyTemplate(Object stack) {
        if (this == ITEM && stack instanceof ItemStack item) return item.copyWithCount(1);
        if (this == FLUID && stack instanceof FluidStack fluid) {
            FluidStack copy = fluid.copy();
            copy.setAmount(1);
            return copy;
        }
        throw new IllegalArgumentException("资源与类别不一致");
    }

    public int amount(Object stack) {
        return this == ITEM ? ((ItemStack) stack).getCount() : ((FluidStack) stack).getAmount();
    }
}
