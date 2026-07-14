package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * C2S: request the current server-authoritative status for one craft, or for
 * every active craft owned by the sender. This repairs client state after a
 * reconnect, HUD reload, or a dropped started/progress packet.
 */
public final class CraftStatusRequestPacket {

    @Nullable
    private final UUID craftId;

    private static final ConcurrentHashMap<UUID, Long> STATUS_COOLDOWN = new ConcurrentHashMap<>();
    private static final long STATUS_COOLDOWN_MS = 500;

    /** Request all active crafts owned by the sender. */
    public CraftStatusRequestPacket() {
        this(null);
    }

    /** Request one craft by stable id. */
    public CraftStatusRequestPacket(@Nullable UUID craftId) {
        this.craftId = craftId;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(craftId != null);
        if (craftId != null) buf.writeUUID(craftId);
    }

    public static CraftStatusRequestPacket decode(FriendlyByteBuf buf) {
        return new CraftStatusRequestPacket(buf.readBoolean() ? buf.readUUID() : null);
    }

    public static void handle(CraftStatusRequestPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            long now = System.currentTimeMillis();
            Long previous = STATUS_COOLDOWN.put(player.getUUID(), now);
            if (previous != null && now - previous < STATUS_COOLDOWN_MS) return;

            AsyncCraftManager manager = AsyncCraftManager.getInstance();
            if (packet.craftId != null) {
                AsyncCraftChain chain = manager.getCraft(packet.craftId);
                if (chain == null || !chain.belongsTo(player.getUUID())) {
                    if (chain != null) {
                        RSIntegrationMod.LOGGER.warn("[RSI-Status] Player {} requested craft {} owned by {}",
                                player.getGameProfile().getName(), packet.craftId, chain.getPlayerId());
                    }
                    return;
                }
                sendStatus(player, chain);
                return;
            }
            for (AsyncCraftChain chain : manager.activeCraftsFor(player.getUUID())) {
                sendStatus(player, chain);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void sendStatus(ServerPlayer player, AsyncCraftChain chain) {
        BatchCraftNetworkHandler.CHANNEL.sendTo(
                new CraftStartedPacket(chain.getCraftId(), chain.stepsCount(), chain.isGraphExecution()),
                player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        BatchCraftNetworkHandler.CHANNEL.sendTo(
                new CraftProgressPacket(chain.nextStatusSnapshot()),
                player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    @Nullable
    public UUID craftId() {
        return craftId;
    }

    public boolean requestsAll() {
        return craftId == null;
    }
}
