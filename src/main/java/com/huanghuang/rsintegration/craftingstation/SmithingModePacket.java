package com.huanghuang.rsintegration.craftingstation;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 客户端请求切换 RS 终端的内嵌锻造台模式。 */
public final class SmithingModePacket {
    private final boolean enabled;
    private final boolean clear;

    public SmithingModePacket(boolean enabled) {
        this.enabled = enabled;
        this.clear = false;
    }

    private SmithingModePacket() {
        this.enabled = true;
        this.clear = true;
    }

    public static SmithingModePacket clear() {
        return new SmithingModePacket();
    }

    public SmithingModePacket(FriendlyByteBuf buf) {
        this.enabled = buf.readBoolean();
        this.clear = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(enabled);
        buf.writeBoolean(clear);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof GridContainerMenu menu)
                    || menu.getGrid().getGridType() != GridType.CRAFTING) {
                return;
            }
            SmithingTerminalAccess access = SmithingTerminalAccess.access(menu);
            if (clear) {
                if (access.rsi$isSmithingMode()) {
                    access.rsi$getSmithingState().returnInputs(player);
                    menu.broadcastChanges();
                }
                return;
            }
            access.rsi$setSmithingMode(enabled);
            access.rsi$refreshCraftingStationSlots();
            menu.broadcastFullState();
        });
        context.setPacketHandled(true);
    }

    public static void register() {
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.SMITHING_MODE,
                SmithingModePacket.class,
                SmithingModePacket::encode,
                SmithingModePacket::new,
                SmithingModePacket::handle);
    }
}
