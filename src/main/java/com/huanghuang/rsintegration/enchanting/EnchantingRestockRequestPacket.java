package com.huanghuang.rsintegration.enchanting;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** Client request to refill the open enchanting table's lapis slot from RS. */
public final class EnchantingRestockRequestPacket {
    public static void encode(EnchantingRestockRequestPacket packet, FriendlyByteBuf buffer) {}

    public static EnchantingRestockRequestPacket decode(FriendlyByteBuf buffer) {
        return new EnchantingRestockRequestPacket();
    }

    public static void handle(EnchantingRestockRequestPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> run(context.getSender()));
        context.setPacketHandled(true);
    }

    private static void run(ServerPlayer player) {
        if (player == null || !(player.containerMenu instanceof EnchantmentMenu menu)) {
            send(player, new EnchantingRestockResultPacket(
                    EnchantingRestockResultPacket.Status.INVALID, 0, 0));
            return;
        }

        var lapisSlot = menu.getSlot(1);
        ItemStack existing = lapisSlot.getItem();
        if (!existing.isEmpty() && !existing.is(Items.LAPIS_LAZULI)) {
            send(player, new EnchantingRestockResultPacket(
                    EnchantingRestockResultPacket.Status.INVALID, 0, 64));
            return;
        }

        int target = Math.min(64, lapisSlot.getMaxStackSize());
        int missing = Math.max(0, target - existing.getCount());
        if (missing == 0) {
            send(player, new EnchantingRestockResultPacket(
                    EnchantingRestockResultPacket.Status.COMPLETE, 0, 0));
            return;
        }

        var endpoint = StorageRestockSupport.resolve(player).orElse(null);
        if (endpoint == null) {
            send(player, new EnchantingRestockResultPacket(
                    EnchantingRestockResultPacket.Status.NO_NETWORK, 0, missing));
            return;
        }
        if (!StorageRestockSupport.canExtract(endpoint, player)) {
            send(player, new EnchantingRestockResultPacket(
                    EnchantingRestockResultPacket.Status.NO_PERMISSION, 0, missing));
            return;
        }

        ItemStack extracted = StorageRestockSupport.extract(endpoint, player,
                new ItemStack(Items.LAPIS_LAZULI), missing);
        int inserted = extracted.getCount();
        if (inserted > 0) {
            if (existing.isEmpty()) lapisSlot.set(extracted.copy());
            else existing.grow(inserted);
            menu.broadcastChanges();
        }
        int remaining = Math.max(0, missing - inserted);
        send(player, new EnchantingRestockResultPacket(
                remaining == 0 ? EnchantingRestockResultPacket.Status.COMPLETE
                        : EnchantingRestockResultPacket.Status.PARTIAL,
                inserted, remaining));
    }

    private static void send(ServerPlayer player, EnchantingRestockResultPacket result) {
        if (player != null) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), result);
        }
    }
}
