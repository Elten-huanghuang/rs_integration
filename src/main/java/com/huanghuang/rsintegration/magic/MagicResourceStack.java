package com.huanghuang.rsintegration.magic;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 混合终端显示用的魔法资源堆，类似 ItemStack / FluidStack。
 *
 * <p>身份由 {@link MagicResourceKind} 决定，数量单独保存；灵魂资源沿用原先的
 * 显示路径，仍然是唯一的灵魂条目。</p>
 */
public class MagicResourceStack {
    private final MagicResourceKind kind;
    private int amount;

    public MagicResourceStack(MagicResourceKind kind, int amount) {
        this.kind = kind;
        this.amount = Math.max(0, amount);
    }

    public MagicResourceKind kind() {
        return kind;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = Math.max(0, amount);
    }

    public MagicResourceStack copy() {
        return new MagicResourceStack(kind, amount);
    }

    /** 单条记录超过统一盘上界时截断；原生单位不会达到这个数量级。 */
    public int cappedAmount() {
        return Math.min(amount, kind.capacity());
    }

    public void writeToPacket(FriendlyByteBuf buf) {
        buf.writeVarInt(kind.ordinal());
        buf.writeVarInt(amount);
    }

    public static MagicResourceStack readFromPacket(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        if (ordinal < 0 || ordinal >= MagicResourceKind.values().length) {
            throw new IllegalArgumentException("未知魔法资源类别");
        }
        return new MagicResourceStack(MagicResourceKind.values()[ordinal], buf.readVarInt());
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || obj instanceof MagicResourceStack other
                && kind == other.kind && amount == other.amount;
    }

    @Override
    public int hashCode() {
        return 31 * kind.ordinal() + amount;
    }
}
