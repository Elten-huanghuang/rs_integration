package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S: player requests cancellation of a running craft.
 * Server validates ownership, idempotency, and rate-limits.
 */
public final class CraftCancelPacket {

    private final UUID craftId;

    public CraftCancelPacket(UUID craftId) {
        this.craftId = craftId;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(craftId);
    }

    public static CraftCancelPacket decode(FriendlyByteBuf buf) {
        return new CraftCancelPacket(buf.readUUID());
    }

    public static void handle(CraftCancelPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            AsyncCraftManager mgr = AsyncCraftManager.getInstance();
            AsyncCraftChain chain = mgr.getCraft(packet.craftId);
            if (chain == null) {
                player.sendSystemMessage(Component.translatable("rsi.async.no_chain"));
                return;
            }
            if (!chain.belongsTo(player.getUUID())) {
                RSIntegrationMod.LOGGER.warn("[RSI-Cancel] Player {} tried to cancel craft {} owned by {}",
                        player.getGameProfile().getName(), packet.craftId, chain.getPlayerId());
                return;
            }
            if (chain.isDone()) return;
            chain.abort("Cancelled");
            player.sendSystemMessage(Component.translatable("rsi.async.cancelled"));
        });
        ctx.get().setPacketHandled(true);
    }

    public UUID craftId() { return craftId; }
}
