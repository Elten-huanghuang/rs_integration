package com.huanghuang.rsintegration.compat.ftbquests;

import net.minecraft.world.item.ItemStack;
import java.util.Objects;

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

    public boolean contentEquals(QuestItemRequirement other) {
        if (other == null || taskId != other.taskId
                || required != other.required || progress != other.progress
                || consumesResources != other.consumesResources
                || onlyFromCrafting != other.onlyFromCrafting
                || taskScreenOnly != other.taskScreenOnly || optional != other.optional
                || !ItemStack.matches(displayStack, other.displayStack)
                || validDisplayItems.size() != other.validDisplayItems.size()) {
            return false;
        }
        for (int i = 0; i < validDisplayItems.size(); i++) {
            if (!ItemStack.matches(validDisplayItems.get(i), other.validDisplayItems.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Compares only state that can change the JEI recipe layout. */
    public boolean jeiContentEquals(QuestItemRequirement other) {
        return other != null && taskId == other.taskId
                && jeiDisplayCount(remaining()) == jeiDisplayCount(other.remaining())
                && sameItem(displayStack, other.displayStack)
                && sameItemSet(validDisplayItems, other.validDisplayItems);
    }

    private static int jeiDisplayCount(long remaining) {
        return (int) Math.max(1L, Math.min(64L, remaining));
    }

    private static boolean sameItem(ItemStack left, ItemStack right) {
        return left.getItem() == right.getItem()
                && Objects.equals(left.getTag(), right.getTag());
    }

    private static boolean sameItemSet(List<ItemStack> left, List<ItemStack> right) {
        if (left.size() != right.size()) return false;
        boolean[] matched = new boolean[right.size()];
        for (ItemStack stack : left) {
            boolean found = false;
            for (int i = 0; i < right.size(); i++) {
                if (!matched[i] && sameItem(stack, right.get(i))) {
                    matched[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }
}
