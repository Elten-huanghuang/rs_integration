package com.huanghuang.rsintegration.crafting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for {@link ExtractionLedger}'s terminal-state guards
 * and reservation-token bookkeeping — the network/player-independent core of
 * the stage-0 "idempotent terminal guard" exit condition.
 *
 * <p><b>Scope limit:</b> this is a layer-1 test (no {@link
 * com.huanghuang.rsintegration.testutil.BootstrapTest}). It exercises only
 * paths that do NOT reach {@code transition()} — which touches {@code
 * RSIntegrationMod.LOGGER}, whose {@code FMLJavaModLoadingContext} static init
 * fails without a Forge runtime. It also avoids {@code reserve*()}, whose entry
 * creation requires a live {@code ServerPlayer} and an {@code INetwork} (the RS
 * API is {@code compileOnly} and absent at test runtime). The physical
 * refund/commit conservation paths (invariant 7) are therefore out of reach
 * here and must be covered by the real-Forge end-to-end suite.</p>
 */
class ExtractionLedgerTest {

    @Test
    void freshLedgerStartsIdleAndEmpty() {
        ExtractionLedger ledger = new ExtractionLedger();
        assertEquals(ExtractionLedger.State.IDLE, ledger.state());
        assertEquals(0, ledger.size());
        assertFalse(ledger.isCommitted());
    }

    @Test
    void rollbackOnIdleIsNoopAndIdempotent() {
        ExtractionLedger ledger = new ExtractionLedger();
        // rollback() returns early for IDLE/ROLLED_BACK without a state change.
        ledger.rollback(null);
        assertEquals(ExtractionLedger.State.IDLE, ledger.state());
        ledger.rollback(null);
        assertEquals(ExtractionLedger.State.IDLE, ledger.state());
    }

    @Test
    void closeOnEmptyIdleLedgerIsIdempotent() {
        ExtractionLedger ledger = new ExtractionLedger();
        ledger.close();
        assertEquals(ExtractionLedger.State.ROLLED_BACK, ledger.state());
        // Second close() is a no-op terminal guard — no exception, no state churn.
        ledger.close();
        assertEquals(ExtractionLedger.State.ROLLED_BACK, ledger.state());
    }

    @Test
    void resetReturnsLedgerToIdle() {
        ExtractionLedger ledger = new ExtractionLedger();
        ledger.close();
        assertEquals(ExtractionLedger.State.ROLLED_BACK, ledger.state());
        ledger.reset();
        assertEquals(ExtractionLedger.State.IDLE, ledger.state());
        assertEquals(0, ledger.size());
    }

    @Test
    void reservationMarkOnIdleIsZero() {
        ExtractionLedger ledger = new ExtractionLedger();
        assertEquals(0, ledger.reservationMark());
    }

    @Test
    void tokenSinceZeroIsEmptyWhenNothingReserved() {
        ExtractionLedger ledger = new ExtractionLedger();
        ExtractionLedger.ReservationToken token = ledger.tokenSince(0);
        assertTrue(token.entryIds().isEmpty());
    }

    @Test
    void tokenSinceRejectsOutOfRangeMark() {
        ExtractionLedger ledger = new ExtractionLedger();
        assertThrows(IllegalArgumentException.class, () -> ledger.tokenSince(-1));
        assertThrows(IllegalArgumentException.class, () -> ledger.tokenSince(1));
    }

    @Test
    void cancelReservationsSinceRejectsInvalidMarkAndAcceptsCurrentEnd() {
        ExtractionLedger ledger = new ExtractionLedger();

        ledger.cancelReservationsSince(0);

        assertEquals(0, ledger.size());
        assertThrows(IllegalArgumentException.class, () -> ledger.cancelReservationsSince(-1));
        assertThrows(IllegalArgumentException.class, () -> ledger.cancelReservationsSince(1));
    }

    @Test
    void settleCommittedRejectedWhenNotCommitted() {
        ExtractionLedger ledger = new ExtractionLedger();
        ExtractionLedger.ReservationToken token =
                new ExtractionLedger.ReservationToken(List.of(1));
        // requireState(COMMITTED) rejects settlement on an IDLE ledger.
        assertThrows(RSICraftException.class, () -> ledger.settleCommitted(token));
    }

    @Test
    void releaseCommittedEntriesRejectedWhenNotCommitted() {
        ExtractionLedger ledger = new ExtractionLedger();
        assertThrows(RSICraftException.class, () -> ledger.releaseCommittedEntries(List.of()));
    }

    @Test
    void reservationTokenDefensivelyCopiesEntryIds() {
        List<Integer> ids = new ArrayList<>(List.of(1, 2, 3));
        ExtractionLedger.ReservationToken token = new ExtractionLedger.ReservationToken(ids);
        ids.add(4);
        // The token must not observe mutation of the source list.
        assertEquals(List.of(1, 2, 3), token.entryIds());
        // And its own view must be unmodifiable.
        assertThrows(UnsupportedOperationException.class, () -> token.entryIds().add(5));
    }
}
