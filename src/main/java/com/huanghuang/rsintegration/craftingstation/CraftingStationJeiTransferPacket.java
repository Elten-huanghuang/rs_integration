package com.huanghuang.rsintegration.craftingstation;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** JEI 工作站转移：由服务端从 RS 网络或玩家背包权威提取材料。 */
public final class CraftingStationJeiTransferPacket {
    private static final int MAX_SLOTS = 3;
    private static final int MAX_OPTIONS = 256;
    private final List<List<ItemStack>> options;

    public CraftingStationJeiTransferPacket(List<List<ItemStack>> options) {
        this.options = options.stream().limit(MAX_SLOTS)
                .map(list -> list.stream().limit(MAX_OPTIONS).map(ItemStack::copy).toList()).toList();
    }

    public CraftingStationJeiTransferPacket(FriendlyByteBuf buf) {
        int slots = buf.readVarInt();
        if (slots < 0 || slots > MAX_SLOTS) throw new IllegalArgumentException("Invalid station JEI slots");
        List<List<ItemStack>> decoded = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_OPTIONS) throw new IllegalArgumentException("Invalid station JEI options");
            List<ItemStack> entries = new ArrayList<>(count);
            for (int j = 0; j < count; j++) entries.add(buf.readItem());
            decoded.add(entries);
        }
        options = List.copyOf(decoded);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(options.size());
        for (List<ItemStack> entries : options) {
            buf.writeVarInt(entries.size());
            for (ItemStack stack : entries) buf.writeItem(stack);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof GridContainerMenu menu)
                    || menu.getGrid() == null || menu.getGrid().getGridType() != GridType.CRAFTING
                    || !(menu.getGrid() instanceof INetworkAwareGrid aware) || aware.getNetwork() == null
                    || !aware.getNetwork().getSecurityManager().hasPermission(Permission.EXTRACT, player)
                    || CraftingStationAccess.access(menu).rsi$getCraftingStationMode()
                    == CraftingStationMode.CRAFTING) return;
            CraftingStationAccess.access(menu).rsi$getCraftingStationState().fillFromJei(player, options);
        });
        context.setPacketHandled(true);
    }

    public static void register() {
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.CRAFTING_STATION_JEI_TRANSFER,
                CraftingStationJeiTransferPacket.class,
                CraftingStationJeiTransferPacket::encode,
                CraftingStationJeiTransferPacket::new,
                CraftingStationJeiTransferPacket::handle);
    }
}
