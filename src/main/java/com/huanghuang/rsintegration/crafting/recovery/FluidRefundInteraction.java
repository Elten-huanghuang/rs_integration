package com.huanghuang.rsintegration.crafting.recovery;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 对 RS 方块普通右键时先处理手中的退款物品，避免终端界面抢走交互。 */
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FluidRefundInteraction {
    private FluidRefundInteraction() { }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack held = player.getItemInHand(event.getHand());
        if (!(held.getItem() instanceof FluidRefundItem)) return;
        var result = FluidVoucherRecovery.atBlock(player, held, event.getPos());
        if (result == null) return;
        FluidRefundItem.showResult(player, result);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }
}
