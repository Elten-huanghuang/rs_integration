package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** 确认参数使用客户端实际收到的物品身份，执行仍使用服务端保存的原始目标。 */
final class CraftConfirmationTarget {
    private CraftConfirmationTarget() {}

    static ItemStack executionTarget(PlanResponse plan) {
        ItemStack clicked = plan.clickedOutput();
        return clicked == null || clicked.isEmpty() ? plan.targetResult() : clicked;
    }

    static boolean matches(ItemStack planned, ItemStack confirmed) {
        if (planned == null || planned.isEmpty() || confirmed == null || confirmed.isEmpty()
                || !ItemStack.isSameItem(planned, confirmed)) return false;
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            // 与 PlanResponsePacket 使用相同的同步投影，包括模组 getShareTag/readShareTag。
            // 完整物品比较还会检查未传给客户端的 ForgeCaps，不能用于确认网络包。
            InkFluidSupport.writePacket(buffer, planned);
            ItemStack displayed = InkFluidSupport.readPacket(buffer);
            return Objects.equals(displayed.getTag(), confirmed.getTag());
        } finally {
            buffer.release();
        }
    }
}
