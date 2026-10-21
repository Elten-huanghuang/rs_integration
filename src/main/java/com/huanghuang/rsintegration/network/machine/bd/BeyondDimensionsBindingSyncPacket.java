package com.huanghuang.rsintegration.network.machine.bd;

import com.huanghuang.rsintegration.network.binding.BindingInfo;
import com.huanghuang.rsintegration.network.binding.BindingSyncCodec;
import com.huanghuang.rsintegration.machine.client.BeyondDimensionsBindingSyncClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server snapshot of BD terminal machine bindings for standalone clients. */
public final class BeyondDimensionsBindingSyncPacket {
    public static final int CHUNK_SIZE = BindingSyncCodec.CHUNK_SIZE;

    private final List<BindingInfo> bindings;
    private final long generation;
    private final int chunkIndex;
    private final int totalChunks;

    public BeyondDimensionsBindingSyncPacket(List<BindingInfo> bindings) {
        this(bindings, 0L, 0, 1);
    }

    public BeyondDimensionsBindingSyncPacket(List<BindingInfo> bindings, long generation,
                                             int chunkIndex, int totalChunks) {
        this.bindings = bindings == null ? List.of() : List.copyOf(bindings);
        this.generation = generation;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
    }

    public void encode(FriendlyByteBuf buf) {
        BindingSyncCodec.encode(buf, new BindingSyncCodec.Payload(
                bindings, generation, chunkIndex, totalChunks));
    }

    public static BeyondDimensionsBindingSyncPacket decode(FriendlyByteBuf buf) {
        BindingSyncCodec.Payload payload = BindingSyncCodec.decode(buf);
        return new BeyondDimensionsBindingSyncPacket(
                payload.bindings(), payload.generation(),
                payload.chunkIndex(), payload.totalChunks());
    }

    public static void handle(BeyondDimensionsBindingSyncPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT, () -> () -> BeyondDimensionsBindingSyncClientPacketHandler.handle(packet)));
        context.setPacketHandled(true);
    }

    public List<BindingInfo> bindings() {
        return bindings;
    }

    public int chunkIndex() {
        return chunkIndex;
    }

    public int totalChunks() {
        return totalChunks;
    }

    public long generation() {
        return generation;
    }
}
