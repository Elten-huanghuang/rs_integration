package com.huanghuang.rsintegration.api.resonance;

import net.minecraft.world.item.ItemStack;

/**
 * 共振盘中的一个逻辑物品条目。
 *
 * <p>{@code entryId} 只在生成它的 {@link ResonanceInventory} 及其 revision
 * 内有效，不能写入存档或用于长期识别槽位。</p>
 */
public record ResonanceEntry(
        long entryId,
        ItemStack stack
) {
    public ResonanceEntry {
        if (entryId < 0L) {
            throw new IllegalArgumentException(
                    "resonance entry id must be non-negative");
        }
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException(
                    "resonance entry stack must not be empty");
        }
        stack = stack.copy();
    }

    @Override
    public ItemStack stack() {
        return stack.copy();
    }
}
