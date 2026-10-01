package com.huanghuang.rsintegration.unifiedgrid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import java.util.Objects;

/** 会话索引冻结 NBT，避免原版缓存修改数量或模板后破坏哈希表。 */
public final class GridResourceKey {
    private final GridResourceKind kind;
    private final Object type;
    private final CompoundTag tag;
    private final int hash;

    public GridResourceKey(GridResourceKind kind, Object type, CompoundTag tag) {
        this.kind = kind;
        this.type = type;
        this.tag = tag == null ? null : tag.copy();
        // 已冻结的 NBT 只计算一次哈希，重复数量通知复用此 Key。
        this.hash = Objects.hash(kind, type, this.tag);
    }

    public GridResourceKind kind() { return kind; }
    public Object type() { return type; }
    public CompoundTag tag() { return tag == null ? null : tag.copy(); }
    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof GridResourceKey key && hash == key.hash
                && kind == key.kind && Objects.equals(type, key.type) && Objects.equals(tag, key.tag);
    }

    public static GridResourceKey of(GridResourceKind kind, Object stack) {
        if (kind == GridResourceKind.ITEM) {
            ItemStack item = (ItemStack) stack;
            return new GridResourceKey(kind, item.getItem(), item.getTag());
        }
        FluidStack fluid = (FluidStack) stack;
        return new GridResourceKey(kind, fluid.getFluid(), fluid.getTag());
    }
}
