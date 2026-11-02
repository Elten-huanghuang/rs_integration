package com.huanghuang.rsintegration.feature.experience;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fluids.FluidStack;

/** 按原版附魔之瓶的平均经验装瓶，仅用于本模组的液态经验。 */
public final class ExperienceBottleSupport {
    // 原版掉落 3 + nextInt(5) + nextInt(5) 点经验，均值为 7。
    public static final int EXPERIENCE_POINTS_PER_BOTTLE = 7;
    public static final int BOTTLE_AMOUNT = EXPERIENCE_POINTS_PER_BOTTLE
            * ExperienceFluids.MILLIBUCKETS_PER_EXPERIENCE_POINT;

    private ExperienceBottleSupport() {}

    public static boolean canBottle(FluidStack fluid) {
        return !fluid.isEmpty() && ExperienceFluids.EXPERIENCE.isPresent()
                && fluid.getFluid() == ExperienceFluids.EXPERIENCE.get()
                && (!fluid.hasTag() || fluid.getTag().isEmpty());
    }

    public static ItemStack bottle(FluidStack fluid) {
        return canBottle(fluid) ? new ItemStack(Items.EXPERIENCE_BOTTLE) : ItemStack.EMPTY;
    }

    /** 仅供装瓶结果校验，附魔之瓶不提供反向排液。 */
    public static FluidStack fluid(ItemStack bottle) {
        if (!bottle.is(Items.EXPERIENCE_BOTTLE)
                || bottle.hasTag() && !bottle.getTag().isEmpty()
                || !ExperienceFluids.EXPERIENCE.isPresent()) return FluidStack.EMPTY;
        return new FluidStack(ExperienceFluids.EXPERIENCE.get(), BOTTLE_AMOUNT);
    }
}
