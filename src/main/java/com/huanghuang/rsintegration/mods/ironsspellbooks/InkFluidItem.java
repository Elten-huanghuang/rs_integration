package com.huanghuang.rsintegration.mods.ironsspellbooks;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.List;

/** 递归账本中的流体凭据；数量按 mB 计算，写回 RS 时还原为流体。 */
public final class InkFluidItem extends Item {
    public InkFluidItem() { super(new Properties()); }

    @Override
    public Component getName(ItemStack stack) {
        FluidStack fluid = InkFluidSupport.fluid(stack);
        return fluid.isEmpty() ? super.getName(stack) : fluid.getDisplayName();
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("rsi.fluid_recovery.legacy_hint"));
    }

    /** 兼容旧版本已掉落的内部凭据；右键封装成正常的退款物品。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
        }
        if (held.getTag() == null || held.getCount() <= 0) return InteractionResultHolder.fail(held);
        CompoundTag fluid = held.getTag().copy();
        fluid.putInt("Amount", held.getCount());
        try {
            ItemStack voucher = FluidRefundItem.create(fluid, null);
            serverPlayer.setItemInHand(hand, voucher);
            serverPlayer.displayClientMessage(
                    Component.translatable("rsi.fluid_recovery.legacy_transferred"), true);
            return InteractionResultHolder.consume(voucher);
        } catch (IllegalArgumentException invalid) {
            return InteractionResultHolder.fail(held);
        }
    }
}
