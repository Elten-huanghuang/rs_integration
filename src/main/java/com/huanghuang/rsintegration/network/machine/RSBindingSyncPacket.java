package com.huanghuang.rsintegration.network.machine;

import com.huanghuang.rsintegration.network.binding.BindingSyncCodec;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
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
    public static final int CHUNK_SIZE = BindingSyncCodec.CHUNK_SIZE;

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
        BindingSyncCodec.encode(buf, new BindingSyncCodec.Payload(
                bindings, generation, chunkIndex, totalChunks));
    }

    public static RSBindingSyncPacket decode(FriendlyByteBuf buf) {
        BindingSyncCodec.Payload payload = BindingSyncCodec.decode(buf);
        return new RSBindingSyncPacket(
                payload.bindings(), payload.generation(),
                payload.chunkIndex(), payload.totalChunks());
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
