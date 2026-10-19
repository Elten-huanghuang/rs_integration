package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** 无加工状态、可重复倍增的纯兑换配方契约。 */
public interface VirtualExchangeRecipe {
    List<ItemStack> costItems();

    List<ItemStack> resultItems(RegistryAccess access);
}
