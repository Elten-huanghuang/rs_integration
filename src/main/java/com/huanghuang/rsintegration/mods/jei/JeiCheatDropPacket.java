package com.huanghuang.rsintegration.mods.jei;

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

public record JeiCheatDropPacket(ItemStack stack) {
    private static boolean registered;

    public JeiCheatDropPacket {
        stack = stack.copy();
    }

    public static void encode(JeiCheatDropPacket packet, FriendlyByteBuf buffer) {
        buffer.writeItem(packet.stack);
    }

    public static JeiCheatDropPacket decode(FriendlyByteBuf buffer) {
        return new JeiCheatDropPacket(buffer.readItem());
    }

    public static void handle(JeiCheatDropPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            boolean allowed = player.hasPermissions(2);
            ItemStack drop = validatedDrop(packet.stack, allowed);
            if (!allowed) {
                player.sendSystemMessage(Component.translatable("commands.generic.permission"));
                return;
            }
            if (!drop.isEmpty()) player.drop(drop, true);
        });
        context.setPacketHandled(true);
    }

    static ItemStack validatedDrop(ItemStack stack, boolean hasPermission) {
        if (!hasPermission || stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        int count = Math.min(stack.getCount(), stack.getMaxStackSize());
        return count > 0 ? stack.copyWithCount(count) : ItemStack.EMPTY;
    }

    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.JEI_CHEAT_DROP,
                JeiCheatDropPacket.class, JeiCheatDropPacket::encode,
                JeiCheatDropPacket::decode, JeiCheatDropPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        registered = true;
    }
}
