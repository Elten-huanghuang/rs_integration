package com.huanghuang.rsintegration.sidepanel;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * S2C packet: carries the full list of player-locked item registry names.
 * Sent after a lock toggle and on player login.
 */
public final class RSItemLockSyncPacket {

    final List<ResourceLocation> lockedItemIds;

    public RSItemLockSyncPacket(List<ResourceLocation> lockedItemIds) {
        this.lockedItemIds = Collections.unmodifiableList(new ArrayList<>(lockedItemIds));
    }

    void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(lockedItemIds.size());
        for (ResourceLocation rl : lockedItemIds) {
            buf.writeResourceLocation(rl);
        }
    }

    static RSItemLockSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<ResourceLocation> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(buf.readResourceLocation());
        }
        return new RSItemLockSyncPacket(list);
    }

    @OnlyIn(Dist.CLIENT)
    static void handle(RSItemLockSyncPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        var ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            RSSidePanelClient.lockedItems = Set.copyOf(packet.lockedItemIds);
            RSSidePanelClient.displayDirty = true;
        });
        ctx.setPacketHandled(true);
    }
}
