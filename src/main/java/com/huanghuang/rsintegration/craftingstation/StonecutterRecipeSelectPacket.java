package com.huanghuang.rsintegration.craftingstation;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 服务端权威地选择内嵌切石机配方。 */
public final class StonecutterRecipeSelectPacket {
    private final int recipeIndex;

    public StonecutterRecipeSelectPacket(int recipeIndex) {
        this.recipeIndex = recipeIndex;
    }

    public StonecutterRecipeSelectPacket(FriendlyByteBuf buf) {
        recipeIndex = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(recipeIndex);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (!(player != null && player.containerMenu instanceof GridContainerMenu menu)
                    || CraftingStationAccess.access(menu).rsi$getCraftingStationMode()
                    != CraftingStationMode.STONECUTTER) return;
            CraftingStationState state = CraftingStationAccess.access(menu).rsi$getCraftingStationState();
            if (state instanceof StonecutterTerminalState stonecutter
                    && stonecutter.selectRecipe(recipeIndex)) {
                menu.broadcastChanges();
            }
        });
        context.setPacketHandled(true);
    }

    public static void register() {
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.STONECUTTER_RECIPE_SELECT,
                StonecutterRecipeSelectPacket.class,
                StonecutterRecipeSelectPacket::encode,
                StonecutterRecipeSelectPacket::new,
                StonecutterRecipeSelectPacket::handle);
    }
}
