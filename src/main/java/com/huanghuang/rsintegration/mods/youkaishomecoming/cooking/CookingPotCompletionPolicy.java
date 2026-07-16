package com.huanghuang.rsintegration.mods.youkaishomecoming.cooking;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

final class CookingPotCompletionPolicy {

    private CookingPotCompletionPolicy() {}

    static boolean isExpectedResultBlock(BlockState state, ItemStack expected) {
        return !expected.isEmpty()
                && expected.getItem() instanceof BlockItem blockItem
                && state.is(blockItem.getBlock());
    }

    static boolean isIdleBowl(BlockState state, String expectedBlockKey) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(expectedBlockKey);
    }
}
