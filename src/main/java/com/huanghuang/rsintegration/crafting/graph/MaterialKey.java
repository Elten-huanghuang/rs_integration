package com.huanghuang.rsintegration.crafting.graph;

import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Objects;

/** Item and optional NBT identity used by material-flow allocations. */
public record MaterialKey(Item item, @Nullable String tag) {
    public MaterialKey {
        Objects.requireNonNull(item, "item");
    }

    public static MaterialKey of(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        String tag = stack.hasTag() && !stack.getTag().isEmpty() ? stack.getTag().toString() : null;
        return new MaterialKey(stack.getItem(), tag);
    }

    public ItemStack toStack(int count) {
        ItemStack stack = new ItemStack(item, Math.max(1, count));
        if (tag != null) {
            try {
                stack.setTag(TagParser.parseTag(tag));
            } catch (Exception ignored) {
                // Invalid persisted NBT is rejected by runtime matching later.
            }
        }
        return stack;
    }
}
