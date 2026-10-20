package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.sidepanel.favorite.MachineFavoriteKey;
import com.huanghuang.rsintegration.sidepanel.favorite.MachineFavoritesSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record MachineFavoritesSyncPacket(List<MachineFavoriteKey> favorites,
                                         List<MachineFavoriteKey> blocked) {
    public MachineFavoritesSyncPacket {
        favorites = List.copyOf(favorites);
        blocked = List.copyOf(blocked);
        if (favorites.size() > MachineFavoritesSavedData.MAX_FAVORITES) {
            throw new IllegalArgumentException("Too many machine favorites");
        }
        if (blocked.size() > MachineFavoritesSavedData.MAX_BLOCKED) {
            throw new IllegalArgumentException("Too many blocked machines");
        }
    }

    public static void encode(MachineFavoritesSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.favorites.size());
        for (MachineFavoriteKey key : packet.favorites) key.encode(buf);
        buf.writeVarInt(packet.blocked.size());
        for (MachineFavoriteKey key : packet.blocked) key.encode(buf);
    }

    public static MachineFavoritesSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MachineFavoritesSavedData.MAX_FAVORITES) {
            throw new IllegalArgumentException("Invalid machine favorite count: " + count);
        }
        List<MachineFavoriteKey> favorites = new ArrayList<>(count);
        for (int index = 0; index < count; index++) favorites.add(MachineFavoriteKey.decode(buf));
        int blockedCount = buf.readVarInt();
        if (blockedCount < 0 || blockedCount > MachineFavoritesSavedData.MAX_BLOCKED) {
            throw new IllegalArgumentException("Invalid blocked machine count: " + blockedCount);
        }
        List<MachineFavoriteKey> blocked = new ArrayList<>(blockedCount);
        for (int index = 0; index < blockedCount; index++) {
            blocked.add(MachineFavoriteKey.decode(buf));
        }
        return new MachineFavoritesSyncPacket(favorites, blocked);
    }

    public static void handle(MachineFavoritesSyncPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MachineFavoritesSyncClientPacketHandler.handle(packet)));
        context.setPacketHandled(true);
    }
}
