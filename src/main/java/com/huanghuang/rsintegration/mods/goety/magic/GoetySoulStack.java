package com.huanghuang.rsintegration.mods.goety.magic;

import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceStack;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 灵魂能量的资源视图。
 *
 * <p>灵魂只是 {@link MagicResourceKind#SOUL} 这一种魔法资源，身份、数量编解码和
 * 容量规则都在通用类型里，这里只保留灵魂特有的构造和网络读写入口。</p>
 */
public final class GoetySoulStack extends MagicResourceStack {
    public GoetySoulStack(long amount) {
        super(MagicResourceKind.SOUL, amount);
    }

    @Override
    public GoetySoulStack copy() {
        return new GoetySoulStack(getAmount());
    }

    @Override
    public void writeToPacket(FriendlyByteBuf buf) {
        buf.writeVarLong(getAmount());
    }

    public static GoetySoulStack readFromPacket(FriendlyByteBuf buf) {
        return new GoetySoulStack(buf.readVarLong());
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || obj instanceof GoetySoulStack other && getAmount() == other.getAmount();
    }

    @Override
    public int hashCode() {
        return Long.hashCode(getAmount());
    }
}
