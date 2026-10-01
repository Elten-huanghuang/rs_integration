package com.huanghuang.rsintegration.unifiedgrid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import java.util.Objects;
import java.util.Arrays;
import com.huanghuang.rsintegration.storage.StorageIdentityBytes;

/** 会话索引冻结 NBT，避免原版缓存修改数量或模板后破坏哈希表。 */
public final class GridResourceKey {
    private final GridResourceKind kind;
    private final Object type;
    private final CompoundTag tag;
    private final int hash;
    private final byte[] identity;
    private final CompoundTag caps;

    public GridResourceKey(GridResourceKind kind, Object type, CompoundTag tag) {
        this(kind, type, tag, null);
    }

    private GridResourceKey(GridResourceKind kind, Object type, CompoundTag tag, CompoundTag caps) {
        this.kind = kind;
        this.type = type;
        this.tag = tag == null ? null : tag.copy();
        this.caps = caps == null ? null : caps.copy();
        // 已冻结的 NBT 只计算一次哈希，重复数量通知复用此 Key。
        CompoundTag identityTag = new CompoundTag();
        if (this.tag != null) identityTag.put("tag", this.tag);
        if (caps != null) identityTag.put("ForgeCaps", caps);
        byte[] canonical;
        try { canonical = StorageIdentityBytes.exact(identityTag); }
        catch (IllegalArgumentException unsupported) { canonical = null; }
        identity = canonical;
        // 旧盘可能已有 NaN/超大 NBT；仍按原版 tag 比较显示，统一盘自身拒绝新写入。
        this.hash = identity == null ? Objects.hash(kind, type, this.tag, this.caps)
                : 31 * Objects.hash(kind, type) + Arrays.hashCode(identity);
    }

    public GridResourceKind kind() { return kind; }
    public Object type() { return type; }
    public CompoundTag tag() { return tag == null ? null : tag.copy(); }
    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof GridResourceKey key && hash == key.hash
                && kind == key.kind && Objects.equals(type, key.type)
                && (identity == null ? key.identity == null && Objects.equals(tag, key.tag) && Objects.equals(caps, key.caps)
                : Arrays.equals(identity, key.identity));
    }

    public static GridResourceKey of(GridResourceKind kind, Object stack) {
        if (kind == GridResourceKind.ITEM) {
            ItemStack item = (ItemStack) stack;
            CompoundTag saved = item.save(new CompoundTag());
            return new GridResourceKey(kind, item.getItem(), item.getTag(),
                    saved.contains("ForgeCaps") ? saved.getCompound("ForgeCaps") : null);
        }
        FluidStack fluid = (FluidStack) stack;
        return new GridResourceKey(kind, fluid.getFluid(), fluid.getTag());
    }
}
