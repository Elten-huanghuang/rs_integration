package com.huanghuang.rsintegration.feature.experience;

import com.huanghuang.rsintegration.feature.experience.client.ResonantExperienceWandClient;
import com.huanghuang.rsintegration.util.DiskTooltipEffects;
import com.huanghuang.rsintegration.util.SpecialItemTooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;

/** 共振经验杖：客户端使用独立的立体物品渲染器。 */
public final class ResonantExperienceWandItem extends Item {
    public ResonantExperienceWandItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public Component getName(ItemStack stack) {
        return DiskTooltipEffects.tint(super.getName(stack),
                DiskTooltipEffects.Theme.EXPERIENCE, DiskTooltipEffects.Tone.MAIN);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) return InteractionResultHolder.pass(stack);
        if (player instanceof ServerPlayer serverPlayer) {
            ExperienceWandNetwork.open(serverPlayer, hand);
            player.getCooldowns().addCooldown(this, 5);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;
        return use(context.getLevel(), player, context.getHand()).getResult();
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                               List<Component> tooltip, TooltipFlag flag) {
        SpecialItemTooltip.appendExperience(tooltip, ExperienceWandSettings.fromStack(stack),
                Screen.hasShiftDown());
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        // Forge 只在物理客户端调用此入口，服务端不创建或加载渲染器。
        ResonantExperienceWandClient.initialize(consumer);
    }
}
