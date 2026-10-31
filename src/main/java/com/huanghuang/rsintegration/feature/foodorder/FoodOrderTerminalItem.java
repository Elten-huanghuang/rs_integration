package com.huanghuang.rsintegration.feature.foodorder;

import com.huanghuang.rsintegration.util.DiskTooltipEffects;
import com.huanghuang.rsintegration.util.SpecialItemTooltip;
import net.minecraft.client.gui.screens.Screen;
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

/** 点餐终端：手持右键打开界面。 */
public final class FoodOrderTerminalItem extends Item {

    public FoodOrderTerminalItem() {
        super(new Item.Properties().stacksTo(1).fireResistant());
    }

    @Override
    public Component getName(ItemStack stack) {
        return DiskTooltipEffects.tint(super.getName(stack),
                DiskTooltipEffects.Theme.FOOD, DiskTooltipEffects.Tone.MAIN);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> FoodOrderClient::openScreen);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        SpecialItemTooltip.appendFood(tooltip, Screen.hasShiftDown());
    }
}
