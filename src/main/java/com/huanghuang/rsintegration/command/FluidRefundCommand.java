package com.huanghuang.rsintegration.command;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.unifiedgrid.UnifiedGridFluidRecovery;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 玩家手动领取离线或背包满时保留的流体退款物品。 */
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FluidRefundCommand {
    private FluidRefundCommand() { }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rsi_fluid_refund")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    int claimed = UnifiedGridFluidRecovery.get(player.server).claim(player);
                    Component message = claimed == 0
                            ? Component.translatable("rsi.fluid_recovery.claim_empty")
                            : Component.translatable("rsi.fluid_recovery.claimed", claimed);
                    context.getSource().sendSuccess(() -> message, false);
                    return claimed;
                }));
    }
}
