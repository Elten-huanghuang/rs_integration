package com.huanghuang.rsintegration.mods.wizardsreborn;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.function.Predicate;

public interface WRCrystalOutputHook {
    void rsi$setCrystalOutputReceiver(@Nullable Predicate<ItemStack> receiver);

    boolean rsi$receiveCrystalOutput(ItemStack stack);

    /** 标记已接入实际掉落出口的水晶仪式。 */
    interface NativeInfusion {}
}
