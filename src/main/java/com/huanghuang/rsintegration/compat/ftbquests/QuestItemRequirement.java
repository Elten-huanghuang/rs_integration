package com.huanghuang.rsintegration.compat.ftbquests;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Immutable, side-neutral view of one FTB Quests item-task requirement. */
public record QuestItemRequirement(
        long taskId,
        ItemStack displayStack,
        List<ItemStack> validDisplayItems,
        long required,
        long progress,
        boolean consumesResources,
        boolean onlyFromCrafting,
        boolean taskScreenOnly,
        boolean optional) {

    public QuestItemRequirement {
        displayStack = displayStack.copy();
        validDisplayItems = validDisplayItems.stream().map(ItemStack::copy).toList();
        required = Math.max(0L, required);
        progress = Math.max(0L, Math.min(progress, required));
    }

    public long remaining() {
        return Math.max(0L, required - progress);
    }
}
