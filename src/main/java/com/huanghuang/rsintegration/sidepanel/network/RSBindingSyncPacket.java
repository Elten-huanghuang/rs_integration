package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → Client: pushes updated machine bindings after bind/unbind.
 * Sent immediately so the Hub/tabs/tooltip reflect the change without waiting
 * for the next periodic full sync.
 */
public final class RSBindingSyncPacket {
    public static final int CHUNK_SIZE = 128;
    private static final int MAX_CHUNKS = 32;

    private final List<BindingInfo> bindings;
    private final long generation;
    private final int chunkIndex;
    private final int totalChunks;

    public RSBindingSyncPacket(List<BindingInfo> bindings) {
        this(bindings, 0L, 0, 1);
    }

    public RSBindingSyncPacket(List<BindingInfo> bindings, long generation,
                               int chunkIndex, int totalChunks) {
        this.bindings = bindings;
        this.generation = generation;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarLong(generation);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(totalChunks);
        buf.writeVarInt(bindings.size());
        for (BindingInfo info : bindings) {
            BindingInfo.encode(buf, info);
        }
    }

    public static RSBindingSyncPacket decode(FriendlyByteBuf buf) {
        long generation = buf.readVarLong();
        int chunkIndex = buf.readVarInt();
        int totalChunks = buf.readVarInt();
        if (chunkIndex < 0 || totalChunks <= 0 || totalChunks > MAX_CHUNKS
                || chunkIndex >= totalChunks) {
            throw new IllegalArgumentException("Invalid binding sync chunk header");
        }
        int count = buf.readVarInt();
        if (count < 0 || count > CHUNK_SIZE) {
            throw new IllegalArgumentException("Invalid binding sync chunk size: " + count);
        }
        List<BindingInfo> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(BindingInfo.decode(buf));
        }
        return new RSBindingSyncPacket(list, generation, chunkIndex, totalChunks);
    }

    public static void handle(RSBindingSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT, () -> () -> RSBindingSyncClientPacketHandler.handle(packet)));
        ctx.get().setPacketHandled(true);
    }

    List<BindingInfo> bindings() { return bindings; }
    int chunkIndex() { return chunkIndex; }
    int totalChunks() { return totalChunks; }
    long generation() { return generation; }
}
