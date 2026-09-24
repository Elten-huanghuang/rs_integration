package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
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

        ItemStack template = packet.stack.copyWithCount(1);
        Optional<CraftStorageEndpoint> resolved = StorageRestockSupport.resolve(player);
        if (resolved.isEmpty()) {
            notify(player, "rsi.jei.pull.no_network");
            return;
        }

        CraftStorageEndpoint endpoint = resolved.get();
        if (!StorageRestockSupport.canExtract(endpoint, player)) {
            notify(player, "rsi.jei.pull.no_permission");
            return;
        }

        int capacity = inventoryCapacity(player.getInventory(), template);
        if (capacity <= 0) {
            notify(player, "rsi.jei.pull.inventory_full");
            return;
        }

        int amount = Math.min(template.getMaxStackSize(), capacity);
        ItemStack extracted;
        try {
            extracted = StorageRestockSupport.extract(endpoint, player, template, amount);
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.warn("[RSI-JEI] Storage pull failed for {}", template, exception);
            notify(player, "rsi.jei.pull.failed");
            return;
        }

        if (extracted.isEmpty()) {
            notify(player, "rsi.jei.pull.not_found", template.getHoverName());
            return;
        }

        ItemStack remainder = extracted.copy();
        player.getInventory().add(remainder);
        if (!remainder.isEmpty()) {
            StorageOperationResult refund = endpoint.insert(player, remainder, false);
            ItemStack unreturned = refund.remainder().orElse(remainder);
            if (!unreturned.isEmpty()) player.drop(unreturned, false);
        }
        player.containerMenu.broadcastChanges();
    }

    private static int inventoryCapacity(Inventory inventory, ItemStack template) {
        int capacity = 0;
        for (ItemStack existing : inventory.items) {
            if (existing.isEmpty()) {
                capacity += template.getMaxStackSize();
            } else if (ItemStack.isSameItemSameTags(existing, template)) {
                capacity += Math.max(0, existing.getMaxStackSize() - existing.getCount());
            }
            if (capacity >= template.getMaxStackSize()) return template.getMaxStackSize();
        }
        return capacity;
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
