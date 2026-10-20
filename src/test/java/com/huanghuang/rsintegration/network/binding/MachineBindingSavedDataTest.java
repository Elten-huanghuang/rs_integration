package com.huanghuang.rsintegration.network.binding;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineBindingSavedDataTest {
    private static final ResourceLocation OVERWORLD =
            new ResourceLocation("minecraft", "overworld");

    @Test
    void updatesKeepStableEndpointIdentityAndOwnerIndex() {
        MachineBindingSavedData data = new MachineBindingSavedData();
        UUID bookId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        BlockPos pos = new BlockPos(12, 64, -8);
        MachineEndpointId firstId = MachineEndpointId.create();

        assertTrue(data.put(bookId, ownerId, firstId, entry(pos, "first")));
        MachineEndpointId storedId = data.endpointId(bookId, OVERWORLD, pos);
        long firstRevision = data.endpoints(bookId).get(0).revision();
        assertEquals(firstId, storedId);

        assertTrue(data.put(bookId, ownerId, MachineEndpointId.create(),
                entry(pos, "renamed")));
        assertEquals(storedId, data.endpointId(bookId, OVERWORLD, pos));
        assertEquals("renamed", data.endpoints(bookId).get(0).entry().blockKey());
        assertEquals(bookId, data.endpoints(bookId).get(0).bookId());
        assertTrue(data.endpoints(bookId).get(0).revision() > firstRevision);
        assertTrue(data.booksForOwner(ownerId).contains(bookId));
    }

    @Test
    void roundTripPreservesEndpointIdsOwnersAndSequence() {
        MachineBindingSavedData original = new MachineBindingSavedData();
        UUID firstBook = UUID.randomUUID();
        UUID secondBook = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        MachineEndpointId firstEndpoint = MachineEndpointId.create();
        MachineEndpointId secondEndpoint = MachineEndpointId.create();

        original.put(firstBook, ownerId, firstEndpoint,
                entry(new BlockPos(1, 2, 3), "first"));
        original.put(secondBook, ownerId, secondEndpoint,
                entry(new BlockPos(4, 5, 6), "second"));

        MachineBindingSavedData decoded =
                MachineBindingSavedData.load(original.save(new CompoundTag()));

        assertEquals(firstEndpoint,
                decoded.endpointId(firstBook, OVERWORLD, new BlockPos(1, 2, 3)));
        assertEquals(secondEndpoint,
                decoded.endpointId(secondBook, OVERWORLD, new BlockPos(4, 5, 6)));
        assertTrue(decoded.booksForOwner(ownerId).containsAll(
                List.of(firstBook, secondBook)));
        assertTrue(decoded.endpoints(firstBook).get(0).sequence()
                < decoded.endpoints(secondBook).get(0).sequence());
    }

    @Test
    void removingMachineEverywhereDoesNotLeakOwnerBooks() {
        MachineBindingSavedData data = new MachineBindingSavedData();
        UUID ownerId = UUID.randomUUID();
        UUID firstBook = UUID.randomUUID();
        UUID secondBook = UUID.randomUUID();
        BlockPos removedPos = new BlockPos(7, 70, 9);

        data.put(firstBook, ownerId, MachineEndpointId.create(), entry(removedPos, "first"));
        data.put(secondBook, ownerId, MachineEndpointId.create(), entry(removedPos, "second"));

        assertEquals(2, data.removeEverywhere(OVERWORLD, removedPos));
        assertEquals(0, data.size(firstBook));
        assertEquals(0, data.size(secondBook));
        assertFalse(data.booksForOwner(ownerId).contains(firstBook));
        assertTrue(data.booksForOwner(ownerId).isEmpty());
    }

    @Test
    void endpointCandidatesDoNotReuseIdsAcrossDifferentLocations() {
        MachineBindingSavedData data = new MachineBindingSavedData();
        UUID bookId = UUID.randomUUID();
        MachineEndpointId first = MachineEndpointId.create();
        MachineEndpointId second = MachineEndpointId.create();

        data.put(bookId, first, entry(new BlockPos(1, 1, 1), "first"));
        data.put(bookId, second, entry(new BlockPos(2, 2, 2), "second"));

        assertNotEquals(first, second);
        assertEquals(first, data.endpointId(bookId, OVERWORLD, new BlockPos(1, 1, 1)));
        assertEquals(second, data.endpointId(bookId, OVERWORLD, new BlockPos(2, 2, 2)));
    }

    private static BindingStorage.BindingEntry entry(BlockPos pos, String blockKey) {
        return new BindingStorage.BindingEntry(
                OVERWORLD, pos, blockKey, "example:" + blockKey, null);
    }
}
