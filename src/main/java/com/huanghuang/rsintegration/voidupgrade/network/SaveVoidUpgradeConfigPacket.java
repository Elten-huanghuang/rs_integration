package com.huanghuang.rsintegration.voidupgrade.network;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.voidupgrade.VoidUpgradeConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public record SaveVoidUpgradeConfigPacket(Target target, int slot, VoidUpgradeConfig config) {
    public enum Target { MAIN_HAND, OFF_HAND, MENU_SLOT }

    private static final Map<UUID, Long> LAST_UPDATE = new ConcurrentHashMap<>();

    public static SaveVoidUpgradeConfigPacket hand(InteractionHand hand, VoidUpgradeConfig config) {
        return new SaveVoidUpgradeConfigPacket(hand == InteractionHand.MAIN_HAND
                ? Target.MAIN_HAND : Target.OFF_HAND, -1, config);
    }

    public static SaveVoidUpgradeConfigPacket menuSlot(int slot, VoidUpgradeConfig config) {
        return new SaveVoidUpgradeConfigPacket(Target.MENU_SLOT, slot, config);
    }

    public static void encode(SaveVoidUpgradeConfigPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.target);
        buf.writeVarInt(packet.slot);
        buf.writeNbt(packet.config.toTag());
    }

    public static SaveVoidUpgradeConfigPacket decode(FriendlyByteBuf buf) {
        Target target = buf.readEnum(Target.class);
        int slot = buf.readVarInt();
        return new SaveVoidUpgradeConfigPacket(target, slot,
                VoidUpgradeConfig.fromTag(buf.readNbt()));
    }

    public static void handle(SaveVoidUpgradeConfigPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> apply(context.getSender(), packet));
        context.setPacketHandled(true);
    }

    private static void apply(ServerPlayer player, SaveVoidUpgradeConfigPacket packet) {
        if (player == null || packet.config.rules().size() > VoidUpgradeConfig.MAX_RULES) return;
        long now = player.level().getGameTime();
        Long previous = LAST_UPDATE.put(player.getUUID(), now);
        if (previous != null && previous == now) return;

        if (packet.target == Target.MENU_SLOT) {
            if (packet.slot < 0 || packet.slot >= player.containerMenu.slots.size()) return;
            Slot slot = player.containerMenu.getSlot(packet.slot);
            ItemStack stack = slot.getItem();
            if (!isUpgrade(stack)) return;
            ItemStack updated = stack.copy();
            packet.config.save(updated);
            slot.set(updated);
            slot.setChanged();
            player.containerMenu.broadcastChanges();
            return;
        }

        InteractionHand hand = packet.target == Target.MAIN_HAND
                ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack stack = player.getItemInHand(hand);
        if (!isUpgrade(stack)) return;
        ItemStack updated = stack.copy();
        packet.config.save(updated);
        player.setItemInHand(hand, updated);
        player.getInventory().setChanged();
    }

    private static boolean isUpgrade(ItemStack stack) {
        return !stack.isEmpty() && ModItems.RS_VOID_UPGRADE != null
                && stack.is(ModItems.RS_VOID_UPGRADE.get());
    }

    static void clearServerState() {
        LAST_UPDATE.clear();
    }
}
