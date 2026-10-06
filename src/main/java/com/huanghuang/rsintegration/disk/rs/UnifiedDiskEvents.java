package com.huanghuang.rsintegration.disk.rs;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class UnifiedDiskEvents {
    private UnifiedDiskEvents() {}
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        UnifiedDiskInventoryInitialization.process(event.getServer());
        UnifiedDiskManager manager = UnifiedDiskManager.existing(event.getServer());
        if (manager != null) manager.tick();
    }
    @SubscribeEvent public static void save(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && level == level.getServer().overworld()) {
            UnifiedDiskManager manager = UnifiedDiskManager.existing(level.getServer());
            if (manager != null) manager.flush();
        }
    }
    @SubscribeEvent public static void stop(ServerStoppedEvent event) {
        UnifiedDiskInventoryInitialization.clear(event.getServer());
        UnifiedDiskManager.stop(event.getServer());
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level) {
            if (event.getEntity() instanceof ServerPlayer player) UnifiedDiskInventoryInitialization.forget(level, player);
            UnifiedDiskManager manager = UnifiedDiskManager.existing(level.getServer());
            if (manager != null) manager.forgetTooltipPlayer(event.getEntity().getUUID());
        }
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(UnifiedDiskRecoveryCommands.register(Commands.literal("rsi_unified_disk").requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> {
                    ItemStack held = context.getSource().getPlayerOrException().getMainHandItem();
                    if (!(held.getItem() instanceof UnifiedDiskItem item) || !item.isValid(held)) {
                        context.getSource().sendFailure(Component.literal("请手持已初始化的统一盘")); return 0;
                    }
                    UnifiedDiskManager manager = UnifiedDiskManager.get(context.getSource().getLevel());
                    context.getSource().sendSuccess(() -> Component.literal(manager.diagnostic(item.getId(held))), false);
                    return 1;
                }))));
    }
}
