package com.huanghuang.rsintegration.villager;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record VillagerRestockRequestPacket(int offerIndex) {
    public static void encode(VillagerRestockRequestPacket p, FriendlyByteBuf b) { b.writeVarInt(p.offerIndex); }
    public static VillagerRestockRequestPacket decode(FriendlyByteBuf b) { return new VillagerRestockRequestPacket(b.readVarInt()); }
    public static void handle(VillagerRestockRequestPacket p, Supplier<NetworkEvent.Context> cs) {
        NetworkEvent.Context c=cs.get(); c.enqueueWork(() -> run(c.getSender(), p.offerIndex)); c.setPacketHandled(true);
    }

    private static void run(ServerPlayer player, int index) {
        if (player == null || !(player.containerMenu instanceof MerchantMenu menu)
                || index < 0 || index >= menu.getOffers().size()) {
            send(player, new VillagerRestockResultPacket(VillagerRestockResultPacket.Status.INVALID, 0, 0, List.of()));
            return;
        }
        menu.setSelectionHint(index);
        menu.tryMoveItems(index);
        MerchantOffer offer=menu.getOffers().get(index);
        ItemStack[] costs={offer.getCostA(), offer.getCostB()};
        int inventory=menu.getSlot(0).getItem().getCount()+menu.getSlot(1).getItem().getCount();
        int fromRs=0;
        var network=RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        boolean denied=network != null && network.getSecurityManager() != null
                && !network.getSecurityManager().hasPermission(Permission.EXTRACT, player);
        List<VillagerRestockResultPacket.Missing> missing=new ArrayList<>(2);
        for (int slot=0; slot<2; slot++) {
            ItemStack cost=costs[slot];
            if (cost.isEmpty()) continue;
            ItemStack payment=menu.getSlot(slot).getItem();
            int target=Math.min(64, cost.getMaxStackSize());
            int current=ItemStack.isSameItemSameTags(cost, payment) ? payment.getCount() : 0;
            int needed=Math.max(0, target-current);
            if (needed > 0 && network != null && !denied) {
                ItemStack extracted=RSIntegrationNetwork.extractExactFromNetwork(network, cost, needed, player);
                if (!extracted.isEmpty()) {
                    if (payment.isEmpty()) menu.getSlot(slot).set(extracted.copy());
                    else payment.grow(extracted.getCount());
                    current+=extracted.getCount();
                    fromRs+=extracted.getCount();
                }
            }
            if (current < target) missing.add(new VillagerRestockResultPacket.Missing(cost.copyWithCount(1), target-current));
        }
        menu.broadcastChanges();
        var status=missing.isEmpty() ? VillagerRestockResultPacket.Status.COMPLETE
                : denied ? VillagerRestockResultPacket.Status.NO_PERMISSION
                : network == null ? VillagerRestockResultPacket.Status.NO_NETWORK
                : VillagerRestockResultPacket.Status.PARTIAL;
        send(player, new VillagerRestockResultPacket(status, inventory, fromRs, missing));
    }

    private static void send(ServerPlayer p, VillagerRestockResultPacket result) {
        if (p != null) NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), result);
    }
}
