package com.huanghuang.rsintegration.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

public final class ItemStackUtils {
    /** Maximum safe count for network serialization (FriendlyByteBuf limit ≈ 127 bytes). */
    private static final int MAX_NETWORK_COUNT = 64;

    private ItemStackUtils() {}

    /** Server-safe item identifier for diagnostics. Never resolves a hover name. */
    public static String registryId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "unknown" : id.toString();
    }

    /** Returns true if the stack is safe for network transmission. */
    public static boolean isSafeForNetwork(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        return stack.getCount() <= MAX_NETWORK_COUNT;
    }

    /** Creates a network-safe display copy with count capped at 1 (base multiplier).
     *  For use in PlanResponse — the client knows repeatCount separately. */
    public static ItemStack toNetworkDisplay(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack display = stack.copy();
        if (display.getCount() > 1) display.setCount(1);
        return display;
    }

    /** Clamp count to network-safe maximum. */
    public static ItemStack clampForNetwork(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        if (stack.getCount() > MAX_NETWORK_COUNT) {
            ItemStack safe = stack.copy();
            safe.setCount(MAX_NETWORK_COUNT);
            return safe;
        }
        return stack;
    }

    /**
     * 将没有任何字段的根物品标签视为没有标签。
     * 空 tag 不携带物品身份信息，但 RS 会把它和无 tag 当成两个变体。
     */
    public static ItemStack normalizeEmptyTag(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getTag() == null || !stack.getTag().isEmpty()) {
            return stack;
        }
        ItemStack normalized = stack.copy();
        normalized.setTag(null);
        return normalized;
    }
}
