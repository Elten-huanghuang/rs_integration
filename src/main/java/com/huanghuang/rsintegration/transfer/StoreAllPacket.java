package com.huanghuang.rsintegration.transfer;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class StoreAllPacket {

    // 0 = RS Network, 1 = Backpack
    private final byte mode;

    public StoreAllPacket(byte mode) {
        this.mode = mode;
    }

    public static void encode(StoreAllPacket pkt, FriendlyByteBuf buf) {
        buf.writeByte(pkt.mode);
    }

    public static StoreAllPacket decode(FriendlyByteBuf buf) {
        return new StoreAllPacket(buf.readByte());
    }

    public static void handle(StoreAllPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            if (!RSIntegrationConfig.ENABLE_CONTAINER_TRANSFER.get()) return;

            AbstractContainerMenu menu = player.containerMenu;
            if (menu == null) return;
            if (menu.getClass().getName().contains("InventoryMenu")) return;

            ContainerTransferLogic.transferAll(player, menu, pkt.mode);
        });
        ctx.setPacketHandled(true);
    }
}
