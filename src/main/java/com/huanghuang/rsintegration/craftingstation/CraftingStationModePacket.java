package com.huanghuang.rsintegration.craftingstation;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 客户端请求切换 RS 终端内嵌工作站模式。 */
public final class CraftingStationModePacket {
    private final CraftingStationMode mode;
    private final boolean clear;

    public CraftingStationModePacket(CraftingStationMode mode) {
        this.mode = mode;
        this.clear = false;
    }

    private CraftingStationModePacket() {
        this.mode = CraftingStationMode.CRAFTING;
        this.clear = true;
    }

    public static CraftingStationModePacket clear() {
        return new CraftingStationModePacket();
    }

    public CraftingStationModePacket(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        CraftingStationMode[] modes = CraftingStationMode.values();
        if (ordinal < 0 || ordinal >= modes.length) throw new IllegalArgumentException("Invalid station mode");
        mode = modes[ordinal];
        clear = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(mode.ordinal());
        buf.writeBoolean(clear);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof GridContainerMenu menu)
                    || menu.getGrid().getGridType() != GridType.CRAFTING) return;
            CraftingStationAccess access = CraftingStationAccess.access(menu);
            if (clear) {
                if (access.rsi$getCraftingStationMode() != CraftingStationMode.CRAFTING) {
                    access.rsi$getCraftingStationState().returnInputs(player);
                    menu.broadcastChanges();
                }
                return;
            }
            if (RSIntegrationConfig.REQUIRE_BOUND_MACHINE_FOR_VIRTUAL_STATION.get()
                    && !hasBoundMachine(player, mode)) {
                player.displayClientMessage(Component.translatable(
                        "rsi.generic.error.no_bound_machine",
                        Component.translatable(mode.displayNameKey())), true);
                return;
            }
            access.rsi$setCraftingStationMode(mode);
            menu.initSlots();
            menu.broadcastFullState();
        });
        context.setPacketHandled(true);
    }

    private static boolean hasBoundMachine(ServerPlayer player, CraftingStationMode mode) {
        String typeId = mode.requiredBindingTypeId();
        return typeId == null
                || !AltarBindingRegistry.getBoundMachinesForType(player, ModType.byId(typeId)).isEmpty();
    }

    public static void register() {
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.CRAFTING_STATION_MODE,
                CraftingStationModePacket.class,
                CraftingStationModePacket::encode,
                CraftingStationModePacket::new,
                CraftingStationModePacket::handle);
    }
}
