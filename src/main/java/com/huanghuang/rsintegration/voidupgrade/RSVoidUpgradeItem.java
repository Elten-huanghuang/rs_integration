package com.huanghuang.rsintegration.voidupgrade;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.List;

public final class RSVoidUpgradeItem extends Item {
    public RSVoidUpgradeItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.huanghuang.rsintegration.voidupgrade.client.VoidUpgradeClient
                            .open(hand, stack));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        VoidUpgradeConfig config = VoidUpgradeConfig.fromStack(stack);
        tooltip.add(Component.translatable("item.rs_integration.rs_void_upgrade.tooltip")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.rs_integration.rs_void_upgrade.rules",
                config.rules().size()).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("item.rs_integration.rs_void_upgrade.configure")
                .withStyle(ChatFormatting.YELLOW));
    }
}
