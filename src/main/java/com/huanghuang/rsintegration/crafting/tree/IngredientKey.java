package com.huanghuang.rsintegration.crafting.tree;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Item identity key for recipe tree matching and state reconciliation.
 * <p>
 * <b>Critical:</b> equals/hashCode compare only {@code Item} identity ({@code ==}) and
 * {@code CompoundTag} deep equality. Count is <em>never</em> compared. Two ItemStacks
 * of the same item+NBT but different counts are the same key.
 * <p>
 * This is the foundation for {@code producerByOutput} lookup and
 * {@code collapsedKeys} state reconciliation (4.3). If this contract breaks,
 * fold state silently resets on every PlanResponse refresh.
 */
public final class IngredientKey {
    private final Item item;
    @Nullable
    private final CompoundTag tag;

    private IngredientKey(Item item, @Nullable CompoundTag tag) {
        this.item = item;
        this.tag = tag == null ? null : tag.copy();
    }

    public static IngredientKey of(ItemStack stack) {
        return new IngredientKey(stack.getItem(), stack.getTag());
    }

    public ItemStack stack(int count) {
        ItemStack stack = new ItemStack(item, count);
        if (tag != null) {
            stack.setTag(tag.copy());
        }
        return stack;
    }

    public boolean matches(ItemStack stack) {
        return stack.is(item) && Objects.equals(tag, stack.getTag());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof IngredientKey other)) return false;
        return item == other.item && Objects.equals(tag, other.tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(item, tag);
    }
}
