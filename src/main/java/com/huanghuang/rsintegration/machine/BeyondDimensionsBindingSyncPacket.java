package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server snapshot of BD terminal machine bindings for standalone clients. */
public final class BeyondDimensionsBindingSyncPacket {
    public static final int CHUNK_SIZE = 128;
    private static final int MAX_CHUNKS = 32;

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
        buf.writeVarLong(generation);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(totalChunks);
        buf.writeVarInt(bindings.size());
        for (BindingInfo binding : bindings) BindingInfo.encode(buf, binding);
    }

    public static BeyondDimensionsBindingSyncPacket decode(FriendlyByteBuf buf) {
        long generation = buf.readVarLong();
        int chunkIndex = buf.readVarInt();
        int totalChunks = buf.readVarInt();
        if (chunkIndex < 0 || totalChunks <= 0 || totalChunks > MAX_CHUNKS
                || chunkIndex >= totalChunks) {
            throw new IllegalArgumentException("Invalid BD binding sync chunk header");
        }
        int count = buf.readVarInt();
        if (count < 0 || count > CHUNK_SIZE) {
            throw new IllegalArgumentException("Invalid BD binding sync chunk size: " + count);
        }
        List<BindingInfo> bindings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) bindings.add(BindingInfo.decode(buf));
        return new BeyondDimensionsBindingSyncPacket(
                bindings, generation, chunkIndex, totalChunks);
    }

    public static void handle(BeyondDimensionsBindingSyncPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT, () -> () -> BeyondDimensionsBindingSyncClientPacketHandler.handle(packet)));
        context.setPacketHandled(true);
    }

    List<BindingInfo> bindings() {
        return bindings;
    }

    int chunkIndex() {
        return chunkIndex;
    }

    int totalChunks() {
        return totalChunks;
    }

    long generation() {
        return generation;
    }
}
