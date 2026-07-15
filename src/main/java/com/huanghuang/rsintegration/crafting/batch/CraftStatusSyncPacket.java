package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftProgressTracker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** S2C reconciliation boundary for craft status requests. */
public final class CraftStatusSyncPacket {

    private static final int MAX_CRAFTS = 4096;
    private static final byte FULL = 0;
    private static final byte NOT_FOUND = 1;

    private final byte mode;
    private final List<UUID> craftIds;

    private CraftStatusSyncPacket(byte mode, List<UUID> craftIds) {
        if (mode != FULL && mode != NOT_FOUND) throw new IllegalArgumentException("invalid sync mode");
        if (craftIds.size() > MAX_CRAFTS) throw new IllegalArgumentException("too many craft ids");
        if (mode == NOT_FOUND && craftIds.size() != 1) {
            throw new IllegalArgumentException("not-found sync requires one craft id");
        }
        this.mode = mode;
        this.craftIds = List.copyOf(craftIds);
    }

    public static CraftStatusSyncPacket full(List<UUID> craftIds) {
        return new CraftStatusSyncPacket(FULL, craftIds);
    }

    public static CraftStatusSyncPacket notFound(UUID craftId) {
        return new CraftStatusSyncPacket(NOT_FOUND, List.of(craftId));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(mode);
        buf.writeVarInt(craftIds.size());
        for (UUID craftId : craftIds) buf.writeUUID(craftId);
    }

    public static CraftStatusSyncPacket decode(FriendlyByteBuf buf) {
        byte mode = buf.readByte();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_CRAFTS) throw new IllegalArgumentException("invalid craft id count");
        List<UUID> craftIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) craftIds.add(buf.readUUID());
        return new CraftStatusSyncPacket(mode, craftIds);
    }

    @OnlyIn(Dist.CLIENT)
    public static void handle(CraftStatusSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (packet.mode == FULL) CraftProgressTracker.retainOnly(packet.craftIds);
            else CraftProgressTracker.remove(packet.craftIds.get(0));
        });
        ctx.get().setPacketHandled(true);
    }

    public boolean fullSync() { return mode == FULL; }
    public List<UUID> craftIds() { return craftIds; }
}
