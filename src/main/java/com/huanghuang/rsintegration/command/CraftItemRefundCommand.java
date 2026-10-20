package com.huanghuang.rsintegration.command;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftItemRecovery;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 自动领取或手动领取离线合成退款。 */
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CraftItemRefundCommand {
    private CraftItemRefundCommand() {
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        CraftItemRecovery recovery = CraftItemRecovery.get(player.server);
        int claimed = recovery.claim(player);
        if (claimed > 0) {
            player.sendSystemMessage(Component.translatable("rsi.item_recovery.claimed", claimed));
        } else if (recovery.hasPending(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("rsi.item_recovery.pending"));
        }
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rsi_item_refund")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    int claimed = CraftItemRecovery.get(player.server).claim(player);
                    Component message = claimed == 0
                            ? Component.translatable("rsi.item_recovery.claim_empty")
                            : Component.translatable("rsi.item_recovery.claimed", claimed);
                    context.getSource().sendSuccess(() -> message, false);
                    return claimed;
                }));
    }
}
