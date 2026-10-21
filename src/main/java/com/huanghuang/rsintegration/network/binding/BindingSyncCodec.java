package com.huanghuang.rsintegration.network.binding;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** RS 与 BD 绑定快照共享的 wire 格式和分块规则。 */
public final class BindingSyncCodec {
    public static final int CHUNK_SIZE = 128;
    public static final int MAX_CHUNKS = 32;

    private BindingSyncCodec() {}

    public record Payload(
            List<BindingInfo> bindings,
            long generation,
            int chunkIndex,
            int totalChunks) {}

    public static void encode(FriendlyByteBuf buffer, Payload payload) {
        buffer.writeVarLong(payload.generation());
        buffer.writeVarInt(payload.chunkIndex());
        buffer.writeVarInt(payload.totalChunks());
        buffer.writeVarInt(payload.bindings().size());
        for (BindingInfo binding : payload.bindings()) {
            BindingInfo.encode(buffer, binding);
        }
    }

    public static Payload decode(FriendlyByteBuf buffer) {
        long generation = buffer.readVarLong();
        int chunkIndex = buffer.readVarInt();
        int totalChunks = buffer.readVarInt();
        if (chunkIndex < 0 || totalChunks <= 0
                || totalChunks > MAX_CHUNKS || chunkIndex >= totalChunks) {
            throw new IllegalArgumentException(
                    "Invalid binding sync chunk header");
        }
        int count = buffer.readVarInt();
        if (count < 0 || count > CHUNK_SIZE) {
            throw new IllegalArgumentException(
                    "Invalid binding sync chunk size: " + count);
        }
        List<BindingInfo> bindings = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            bindings.add(BindingInfo.decode(buffer));
        }
        return new Payload(
                List.copyOf(bindings), generation, chunkIndex, totalChunks);
    }

    public static List<Payload> chunks(
            List<BindingInfo> bindings, long generation) {
        List<BindingInfo> source =
                bindings == null ? List.of() : List.copyOf(bindings);
        int totalChunks = Math.max(
                1, (source.size() + CHUNK_SIZE - 1) / CHUNK_SIZE);
        List<Payload> result = new ArrayList<>(totalChunks);
        for (int chunkIndex = 0;
             chunkIndex < totalChunks; chunkIndex++) {
            int from = chunkIndex * CHUNK_SIZE;
            int to = Math.min(from + CHUNK_SIZE, source.size());
            List<BindingInfo> chunk = from >= source.size()
                    ? List.of() : source.subList(from, to);
            result.add(new Payload(
                    chunk, generation, chunkIndex, totalChunks));
        }
        return List.copyOf(result);
    }
}
