package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/** 合并机器绑定分块快照，避免单个超大包造成服务端与客户端内存峰值。 */
@OnlyIn(Dist.CLIENT)
public final class BindingSyncChunkAccumulator {
    private static long generation = Long.MIN_VALUE;
    private static List<List<BindingInfo>> chunks = List.of();
    private static int receivedChunks;

    private BindingSyncChunkAccumulator() {}

    /**
     * @return 完整快照；尚未收齐或收到重复块时返回 {@code null}
     */
    public static synchronized List<BindingInfo> accept(
            long snapshotGeneration, int chunkIndex, int totalChunks,
            List<BindingInfo> bindings) {
        if (totalChunks <= 1) {
            clearState();
            return List.copyOf(bindings);
        }
        if (snapshotGeneration != generation) {
            generation = snapshotGeneration;
            chunks = new ArrayList<>(totalChunks);
            for (int index = 0; index < totalChunks; index++) chunks.add(null);
            receivedChunks = 0;
        }
        if (chunkIndex < 0 || chunkIndex >= totalChunks) return null;
        if (chunks.get(chunkIndex) != null) return null;

        chunks.set(chunkIndex, List.copyOf(bindings));
        receivedChunks++;
        if (receivedChunks < totalChunks) return null;

        int totalSize = 0;
        for (List<BindingInfo> chunk : chunks) totalSize += chunk.size();
        List<BindingInfo> complete = new ArrayList<>(totalSize);
        for (List<BindingInfo> chunk : chunks) complete.addAll(chunk);
        chunks = List.of();
        receivedChunks = 0;
        generation = Long.MIN_VALUE;
        return List.copyOf(complete);
    }

    public static synchronized void clear() {
        clearState();
    }

    private static void clearState() {
        generation = Long.MIN_VALUE;
        chunks = List.of();
        receivedChunks = 0;
    }
}
