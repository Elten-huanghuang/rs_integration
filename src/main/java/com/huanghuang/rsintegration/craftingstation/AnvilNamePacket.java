package com.huanghuang.rsintegration.craftingstation;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 同步 RS 虚拟铁砧中的重命名输入。 */
public final class AnvilNamePacket {
    private final String name;

    public AnvilNamePacket(String name) {
        this.name = name == null ? "" : name.substring(0, Math.min(50, name.length()));
    }

    public AnvilNamePacket(FriendlyByteBuf buf) {
        this(buf.readUtf(50));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name, 50);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof GridContainerMenu menu)
                    || menu.getGrid() == null || menu.getGrid().getGridType() != GridType.CRAFTING
                    || CraftingStationAccess.access(menu).rsi$getCraftingStationMode()
                    != CraftingStationMode.ANVIL) return;
            AnvilTerminalState state = (AnvilTerminalState)
                    CraftingStationAccess.access(menu).rsi$getCraftingStationState();
            state.setItemName(name);
            menu.broadcastChanges();
        });
        context.setPacketHandled(true);
    }

    public static void register() {
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.ANVIL_NAME,
                AnvilNamePacket.class, AnvilNamePacket::encode,
                AnvilNamePacket::new, AnvilNamePacket::handle);
    }
}
