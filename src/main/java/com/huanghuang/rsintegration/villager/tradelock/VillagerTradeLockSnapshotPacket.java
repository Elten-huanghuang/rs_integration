package com.huanghuang.rsintegration.villager.tradelock;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Replaces the sender's server-side bookmark index with one complete client snapshot. */
public record VillagerTradeLockSnapshotPacket(List<ItemStack> bookmarks) {
    private static boolean registered;

    public VillagerTradeLockSnapshotPacket {
        bookmarks = bookmarks == null ? List.of() : List.copyOf(bookmarks);
    }

    public static void encode(VillagerTradeLockSnapshotPacket packet, FriendlyByteBuf buffer) {
        int size = Math.min(packet.bookmarks.size(), VillagerTradeLockService.MAX_BOOKMARKS);
        buffer.writeVarInt(size);
        for (int i = 0; i < size; i++) buffer.writeItem(packet.bookmarks.get(i));
    }

    public static VillagerTradeLockSnapshotPacket decode(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > VillagerTradeLockService.MAX_BOOKMARKS) {
            throw new IllegalArgumentException("Invalid JEI bookmark snapshot size: " + size);
        }
        List<ItemStack> bookmarks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) bookmarks.add(buffer.readItem());
        return new VillagerTradeLockSnapshotPacket(bookmarks);
    }

    public static void handle(VillagerTradeLockSnapshotPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> VillagerTradeLockService.replace(
                context.getSender(), packet.bookmarks));
        context.setPacketHandled(true);
    }

    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.VILLAGER_TRADE_LOCK_SNAPSHOT,
                VillagerTradeLockSnapshotPacket.class,
                VillagerTradeLockSnapshotPacket::encode,
                VillagerTradeLockSnapshotPacket::decode,
                VillagerTradeLockSnapshotPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        registered = true;
    }
}
