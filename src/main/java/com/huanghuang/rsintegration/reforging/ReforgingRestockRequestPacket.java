package com.huanghuang.rsintegration.reforging;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Set;
import java.util.function.Supplier;

public final class ReforgingRestockRequestPacket {
    private static final ResourceLocation SIGIL_ID = new ResourceLocation("apotheosis", "sigil_of_rebirth");
    private static final Set<String> SUPPORTED_MENUS = Set.of(
            "dev.shadowsoffire.apotheosis.adventure.affix.reforging.ReforgingMenu",
            "com.ianm1647.ancientreforging.screen.AncientReforgingMenu");

    public static void encode(ReforgingRestockRequestPacket packet, FriendlyByteBuf buffer) {}

    public static ReforgingRestockRequestPacket decode(FriendlyByteBuf buffer) {
        return new ReforgingRestockRequestPacket();
    }

    public static void handle(ReforgingRestockRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> run(context.getSender()));
        context.setPacketHandled(true);
    }

    private static void run(ServerPlayer player) {
        if (player == null || !isSupported(player.containerMenu) || player.containerMenu.slots.size() < 3) {
            send(player, new ReforgingRestockResultPacket(ReforgingRestockResultPacket.Status.INVALID, 0, 0));
            return;
        }

        var sigilItem = BuiltInRegistries.ITEM.get(SIGIL_ID);
        ItemStack template = new ItemStack(sigilItem);
        var slot = player.containerMenu.getSlot(2);
        ItemStack existing = slot.getItem();
        if ((!existing.isEmpty() && !ItemStack.isSameItemSameTags(existing, template)) || !slot.mayPlace(template)) {
            send(player, new ReforgingRestockResultPacket(ReforgingRestockResultPacket.Status.INVALID, 0, 0));
            return;
        }

        int target = Math.min(slot.getMaxStackSize(template), template.getMaxStackSize());
        int current = existing.isEmpty() ? 0 : existing.getCount();
        int needed = Math.max(0, target - current);
        if (needed == 0) {
            send(player, new ReforgingRestockResultPacket(ReforgingRestockResultPacket.Status.COMPLETE, 0, 0));
            return;
        }

        int fromInventory = takeFromInventory(player, template, needed);
        int remaining = needed - fromInventory;
        int fromNetwork = 0;
        var network = remaining > 0 ? RSIntegrationNetwork.resolveNetworkFromPlayer(player) : null;
        boolean denied = remaining > 0 && network != null && network.getSecurityManager() != null
                && !network.getSecurityManager().hasPermission(Permission.EXTRACT, player);
        if (remaining > 0 && network != null && !denied) {
            ItemStack extracted = RSIntegrationNetwork.extractExactFromNetwork(
                    network, template, remaining, player);
            fromNetwork = extracted.getCount();
        }
        int inserted = fromInventory + fromNetwork;
        if (inserted > 0) {
            if (existing.isEmpty()) slot.set(template.copyWithCount(inserted));
            else existing.grow(inserted);
            player.containerMenu.broadcastChanges();
        }
        int missing = needed - inserted;
        ReforgingRestockResultPacket.Status status = missing == 0
                ? ReforgingRestockResultPacket.Status.COMPLETE
                : denied ? ReforgingRestockResultPacket.Status.NO_PERMISSION
                : network == null ? ReforgingRestockResultPacket.Status.NO_NETWORK
                : ReforgingRestockResultPacket.Status.PARTIAL;
        send(player, new ReforgingRestockResultPacket(status, inserted, missing));
    }

    private static int takeFromInventory(ServerPlayer player, ItemStack template, int requested) {
        int remaining = requested;
        var inventory = player.getInventory();
        for (int index = 0; index < inventory.items.size() && remaining > 0; index++) {
            ItemStack stack = inventory.items.get(index);
            if (!ItemStack.isSameItemSameTags(stack, template)) continue;
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            remaining -= taken;
        }
        inventory.setChanged();
        return requested - remaining;
    }

    private static boolean isSupported(AbstractContainerMenu menu) {
        return menu != null && SUPPORTED_MENUS.contains(menu.getClass().getName());
    }

    private static void send(ServerPlayer player, ReforgingRestockResultPacket result) {
        if (player != null) NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), result);
    }
}
