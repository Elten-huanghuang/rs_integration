package com.huanghuang.rsintegration.mods.farmersdelight;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

final class CuttingBoardToolSemantics {

    private CuttingBoardToolSemantics() {}

    static boolean canPerformOperations(ItemStack tool, int operations) {
        if (tool == null || tool.isEmpty() || operations <= 0) return false;
        return !tool.isDamageableItem()
                || tool.getMaxDamage() - tool.getDamageValue() >= operations;
    }

    static int remainingDurability(ItemStack tool) {
        return tool == null || tool.isEmpty() || !tool.isDamageableItem()
                ? Integer.MAX_VALUE
                : Math.max(0, tool.getMaxDamage() - tool.getDamageValue());
    }

    static void damageOnce(ItemStack tool, RandomSource random,
                           @Nullable ServerPlayer player) {
        if (tool == null || tool.isEmpty() || !tool.isDamageableItem()) return;
        if (tool.hurt(1, random, player)) tool.shrink(1);
    }
}
