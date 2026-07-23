package com.huanghuang.rsintegration.compat.ftbquests;

import net.minecraft.world.item.ItemStack;

/** Client-safe preview of one concrete item reward. */
public record QuestItemRewardPreview(long rewardId, ItemStack stack) {

    public QuestItemRewardPreview {
        stack = stack.copy();
    }

    public boolean contentEquals(QuestItemRewardPreview other) {
        return other != null && rewardId == other.rewardId && ItemStack.matches(stack, other.stack);
    }
}
