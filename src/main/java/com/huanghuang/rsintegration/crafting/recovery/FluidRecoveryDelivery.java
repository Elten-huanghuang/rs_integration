package com.huanghuang.rsintegration.crafting.recovery;

import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

public final class FluidRecoveryDelivery {
    private FluidRecoveryDelivery() {}

    public static boolean giveOnline(@Nullable ServerPlayer player, CompoundTag fluid, @Nullable String networkId) {
        if (player == null || player.hasDisconnected() || player.getInventory() == null
                || player.getInventory().getFreeSlot() < 0 || player.level() == null
                || !player.level().hasChunkAt(player.blockPosition())) return false;
        PlayerUtils.safeGiveToStorage(player, FluidRefundItem.create(fluid, networkId), null);
        player.displayClientMessage(Component.translatable("rsi.fluid_recovery.item_received"), true);
        return true;
    }

    public static int claim(ServerPlayer player) {
        return claim(FluidRecoveryStore.get(player.server), player);
    }

    static int claim(FluidRecoveryStore store, ServerPlayer player) {
        var inventory = player.getInventory();
        int claimed = store.deliver(player.getUUID(), () -> inventory.getFreeSlot() >= 0,
                (fluid, network) -> inventory.add(FluidRefundItem.create(fluid, network)));
        if (claimed > 0) sync(player);
        return claimed;
    }

    static void sync(ServerPlayer player) {
        player.getInventory().setChanged();
        if (player.inventoryMenu != null) player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastChanges();
        }
    }
}
