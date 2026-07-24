package com.huanghuang.rsintegration.mods.apotheosis;

import com.huanghuang.rsintegration.mods.apotheosis.network.ApothSpawnerStatePacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.refinedmods.refinedstorage.item.NetworkItem;
import dev.shadowsoffire.apotheosis.spawn.spawner.ApothSpawnerTile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

public final class ApothSpawnerInteractionHandler {
    private ApothSpawnerInteractionHandler() {}

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !event.getEntity().isShiftKeyDown()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack held = event.getItemStack();
        if (held.isEmpty() || !NetworkItem.isValid(held)) return;
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof ApothSpawnerTile)) return;

        var snapshot = ApothSpawnerUpgradeService.scan(player,
                event.getLevel().dimension().location(), event.getPos(), "");
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                ApothSpawnerStatePacket.from(snapshot));
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }
}
