package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.api.RSIItemRetrieval;
import com.huanghuang.rsintegration.api.RSIItemRetrieval.*;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.Optional;
import java.util.function.Supplier;

/** Server-authoritative request to pull one stack shown by JEI from storage. */
public record JeiStoragePullPacket(ItemStack stack) {
    private static boolean registered;

    public JeiStoragePullPacket {
        stack = stack == null ? ItemStack.EMPTY : stack.copy();
    }

    public static void encode(JeiStoragePullPacket packet, FriendlyByteBuf buffer) {
        buffer.writeItem(packet.stack);
    }

    public static JeiStoragePullPacket decode(FriendlyByteBuf buffer) {
        return new JeiStoragePullPacket(buffer.readItem());
    }

    public static void handle(JeiStoragePullPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> handleOnServer(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void handleOnServer(JeiStoragePullPacket packet, ServerPlayer player) {
        if (player == null || packet.stack.isEmpty()) return;

        Result result = RSIItemRetrieval.retrieve(new Request(player, packet.stack, packet.stack.getMaxStackSize(),
                Scene.JEI, Intent.STACK, new Inventory(), Fallback.REGISTERED));
        switch (result.status()) {
            case NO_NETWORK -> notify(player, "rsi.jei.pull.no_network");
            case NO_PERMISSION -> notify(player, "rsi.jei.pull.no_permission");
            case NO_SPACE -> notify(player, "rsi.jei.pull.inventory_full");
            case OUT_OF_STOCK -> notify(player, "rsi.jei.pull.not_found", packet.stack.getHoverName());
            case BACKEND_FAILURE, INDETERMINATE -> notify(player, "rsi.jei.pull.failed");
            default -> {}
        }
    }

    private static void notify(ServerPlayer player, String key, Object... args) {
        player.sendSystemMessage(Component.translatable(key, args));
    }

    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.JEI_STORAGE_PULL,
                JeiStoragePullPacket.class, JeiStoragePullPacket::encode,
                JeiStoragePullPacket::decode, JeiStoragePullPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        registered = true;
    }
}
