package com.huanghuang.rsintegration.magic;

import com.wintercogs.beyonddimensions.api.longtype.LongType;
import net.minecraft.network.chat.Component;

/** BD 库存中的 Goety 灵魂数量包装，不表示图腾物品。 */
public final class GoetySoulType extends LongType<GoetySoulType> {
    public GoetySoulType(long amount) { this.stackCount = amount; }

    @Override public Component getName() {
        return Component.translatable("types.rs_integration.goety_soul.name");
    }

    @Override public GoetySoulType getEmpty() { return new GoetySoulType(0); }
    @Override public GoetySoulType copy() { return new GoetySoulType(stackCount); }
    @Override public GoetySoulType copyWithAmount(long amount) { return new GoetySoulType(amount); }
}
