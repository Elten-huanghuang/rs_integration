package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BindingSyncCodecTest extends BootstrapTest {
    @Test
    void splitsLargeSnapshotsAtTheWireChunkBoundary() {
        List<BindingInfo> bindings = new ArrayList<>();
        for (int index = 0; index < BindingSyncCodec.CHUNK_SIZE + 1; index++) {
            bindings.add(binding(index));
        }

        List<BindingSyncCodec.Payload> chunks =
                BindingSyncCodec.chunks(bindings, 9L);

        assertEquals(2, chunks.size());
        assertEquals(BindingSyncCodec.CHUNK_SIZE,
                chunks.get(0).bindings().size());
        assertEquals(1, chunks.get(1).bindings().size());
        assertEquals(9L, chunks.get(0).generation());
        assertEquals(1, chunks.get(1).chunkIndex());
    }

    @Test
    void emptySnapshotStillSendsOneChunk() {
        List<BindingSyncCodec.Payload> chunks =
                BindingSyncCodec.chunks(List.of(), 4L);

        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).bindings().size());
        assertEquals(1, chunks.get(0).totalChunks());
    }

    private static BindingInfo binding(int index) {
        return new BindingInfo(
                "minecraft:stone", ResourceLocation.tryParse("minecraft:overworld"),
                new BlockPos(index, 0, 0), "block.minecraft.stone",
                "block.minecraft.stone", "minecraft:stone",
                ItemStack.EMPTY, null, null);
    }
}
