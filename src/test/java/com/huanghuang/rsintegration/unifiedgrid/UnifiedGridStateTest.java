package com.huanghuang.rsintegration.unifiedgrid;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedGridStateTest {
    private final UUID session = UUID.randomUUID();
    private UnifiedGridUpdatePacket packet(GridResourceKind kind, int epoch, int sequence, boolean begin, boolean end) {
        return new UnifiedGridUpdatePacket(7, session, kind, epoch, sequence, begin, end, true, true, new byte[0]);
    }
    private UnifiedGridEntry row(int serial, int amount) {
        return new UnifiedGridEntry(serial, amount, true, UUID.randomUUID(), null, new Object(), null);
    }

    @Test void interleavedSnapshotsCommitIndependentlyAndNeverClearOtherKind() {
        UnifiedGridState state = new UnifiedGridState();
        state.apply(packet(GridResourceKind.ITEM, 1, 0, true, false), List.of(row(1, 5)));
        state.apply(packet(GridResourceKind.FLUID, 1, 0, true, true), List.of(row(2, 1000)));
        assertTrue(state.entries(GridResourceKind.ITEM).isEmpty());
        assertEquals(1000, state.get(GridResourceKind.FLUID, 2).amount());
        state.apply(packet(GridResourceKind.ITEM, 1, 1, false, true), List.of());
        state.apply(packet(GridResourceKind.ITEM, 2, 0, true, false), List.of(row(3, 6)));
        assertEquals(5, state.get(GridResourceKind.ITEM, 1).amount());
        assertEquals(1000, state.get(GridResourceKind.FLUID, 2).amount());
        state.apply(packet(GridResourceKind.ITEM, 2, 1, false, true), List.of());
        assertNull(state.get(GridResourceKind.ITEM, 1));
        assertNotNull(state.get(GridResourceKind.FLUID, 2));
    }

    @Test void absoluteAmountsAreIdempotentAndKeepIdentityAtIntLimit() {
        UnifiedGridState state = new UnifiedGridState();
        UnifiedGridEntry original = row(1, 5);
        state.apply(packet(GridResourceKind.ITEM, 1, 0, true, true), List.of(original));
        var update = new UnifiedGridEntry(1, Integer.MAX_VALUE, false, null, null, null, null);
        assertEquals(UnifiedGridState.Result.DELTA, state.apply(packet(GridResourceKind.ITEM, 1, 1, false, false), List.of(update)));
        assertEquals(Integer.MAX_VALUE, state.get(GridResourceKind.ITEM, 1).amount());
        assertEquals(original.storedId(), state.get(GridResourceKind.ITEM, 1).storedId());
        assertSame(original.template(), state.get(GridResourceKind.ITEM, 1).template());
        assertEquals(UnifiedGridState.Result.IGNORED, state.apply(packet(GridResourceKind.ITEM, 1, 1, false, false), List.of(update)));
    }

    @Test void gapAndUnknownSerialRequireNewSnapshotWithoutApplyingPartialChanges() {
        UnifiedGridState state = new UnifiedGridState();
        state.apply(packet(GridResourceKind.ITEM, 1, 0, true, true), List.of(row(1, 5)));
        assertEquals(UnifiedGridState.Result.RESYNC, state.apply(packet(GridResourceKind.ITEM, 1, 2, false, false), List.of()));
        assertFalse(state.ready(GridResourceKind.ITEM));
        state.apply(packet(GridResourceKind.ITEM, 2, 0, true, true), List.of(row(3, 8)));
        assertTrue(state.ready(GridResourceKind.ITEM));
        assertEquals(UnifiedGridState.Result.RESYNC, state.apply(packet(GridResourceKind.ITEM, 2, 1, false, false),
                List.of(new UnifiedGridEntry(3, 9, false, null, null, null, null),
                        new UnifiedGridEntry(99, 7, false, null, null, null, null))));
        assertEquals(8, state.get(GridResourceKind.ITEM, 3).amount());
    }

    @Test void staleEpochIsIgnoredAndDeletedSerialCannotAffectReinsertedRow() {
        UnifiedGridState state = new UnifiedGridState();
        state.apply(packet(GridResourceKind.ITEM, 1, 0, true, true), List.of(row(1, 2)));
        state.apply(packet(GridResourceKind.ITEM, 1, 1, false, false), List.of(new UnifiedGridEntry(1, 0, true, null, null, null, null)));
        state.apply(packet(GridResourceKind.ITEM, 1, 2, false, false), List.of(row(2, 4)));
        assertNull(state.get(GridResourceKind.ITEM, 1));
        assertEquals(4, state.get(GridResourceKind.ITEM, 2).amount());
        state.apply(packet(GridResourceKind.ITEM, 2, 0, true, true), List.of(row(3, 9)));
        assertEquals(UnifiedGridState.Result.IGNORED, state.apply(packet(GridResourceKind.ITEM, 1, 3, false, false), List.of(row(2, 99))));
        assertNull(state.get(GridResourceKind.ITEM, 2));
    }

    @Test void sessionRotationDropsBothPreviousKindsAndRejectsUnsolicitedDelta() {
        UnifiedGridState state = new UnifiedGridState();
        state.apply(packet(GridResourceKind.ITEM, 1, 0, true, true), List.of(row(1, 2)));
        state.apply(packet(GridResourceKind.FLUID, 1, 0, true, true), List.of(row(2, 1000)));
        UUID next = UUID.randomUUID();
        var update = new UnifiedGridUpdatePacket(7, next, GridResourceKind.ITEM, 1, 1, false, false, true, false, new byte[0]);
        assertEquals(UnifiedGridState.Result.IGNORED, state.apply(update, List.of()));
        update = new UnifiedGridUpdatePacket(7, next, GridResourceKind.ITEM, 1, 0, true, true, true, false, new byte[0]);
        state.apply(update, List.of(row(1, 7)));
        assertTrue(state.entries(GridResourceKind.FLUID).isEmpty());
        assertEquals(next, state.session());
        assertEquals(UnifiedGridState.Result.IGNORED,
                state.apply(packet(GridResourceKind.FLUID, 2, 0, true, true), List.of(row(2, 999))));
        assertEquals(next, state.session());
    }
}
