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

/** JEI 锻造台一键填充：候选材料由服务端按 RS 工匠台语义权威提取。 */
public final class SmithingJeiTransferPacket {
    private static final int MAX_SLOTS = 3;
    private static final int MAX_OPTIONS = 256;
    private final List<List<ItemStack>> options;

    public SmithingJeiTransferPacket(List<List<ItemStack>> options) {
        this.options = options.stream().limit(MAX_SLOTS)
                .map(list -> list.stream().limit(MAX_OPTIONS).map(ItemStack::copy).toList())
                .toList();
    }

    public SmithingJeiTransferPacket(FriendlyByteBuf buf) {
        int slotCount = buf.readVarInt();
        if (slotCount < 0 || slotCount > MAX_SLOTS) {
            throw new IllegalArgumentException("Invalid smithing JEI slot count: " + slotCount);
        }
        List<List<ItemStack>> decoded = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            int optionCount = buf.readVarInt();
            if (optionCount < 0 || optionCount > MAX_OPTIONS) {
                throw new IllegalArgumentException("Invalid smithing JEI option count: " + optionCount);
            }
            List<ItemStack> slotOptions = new ArrayList<>(optionCount);
            for (int j = 0; j < optionCount; j++) slotOptions.add(buf.readItem());
            decoded.add(slotOptions);
        }
        options = List.copyOf(decoded);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(options.size());
        for (List<ItemStack> slotOptions : options) {
            buf.writeVarInt(slotOptions.size());
            for (ItemStack stack : slotOptions) buf.writeItem(stack);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof GridContainerMenu menu)
                    || menu.getGrid() == null
                    || menu.getGrid().getGridType() != GridType.CRAFTING
                    || !(menu.getGrid() instanceof INetworkAwareGrid aware)
                    || aware.getNetwork() == null
                    || !aware.getNetwork().getSecurityManager().hasPermission(Permission.EXTRACT, player)) {
                return;
            }
            SmithingTerminalAccess access = SmithingTerminalAccess.access(menu);
            if (!access.rsi$isSmithingMode()) {
                return;
            }
            access.rsi$getSmithingState().fillFromJei(player, options);
        });
        context.setPacketHandled(true);
    }

    public static void register() {
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.SMITHING_JEI_TRANSFER,
                SmithingJeiTransferPacket.class,
                SmithingJeiTransferPacket::encode,
                SmithingJeiTransferPacket::new,
                SmithingJeiTransferPacket::handle);
    }
}
