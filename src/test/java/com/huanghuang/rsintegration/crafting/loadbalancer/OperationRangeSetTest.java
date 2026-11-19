package com.huanghuang.rsintegration.crafting.loadbalancer;

import org.junit.jupiter.api.Test;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class OperationRangeSetTest {
    @Test
    void disjointCompletionFailureAndRepeatSettlementPreserveExactMembership() {
        int total = 200;
        OperationRangeSet ranges = new OperationRangeSet(total);
        BitSet expected = new BitSet(total);
        expected.set(0, total);
        Random random = new Random(7);
        for (int attempt = 0; attempt < 2_000; attempt++) {
            int first = random.nextInt(total);
            int end = first + 1 + random.nextInt(total - first);
            if (random.nextBoolean()) {
                ranges.add(first, end);
                expected.set(first, end);
            } else {
                int removed = ranges.remove(first, end).stream().mapToInt(OperationRangeSet.Range::count).sum();
                assertEquals(expected.get(first, end).cardinality(), removed);
                expected.clear(first, end);
            }
            for (int i = 0; i < total; i++) assertEquals(expected.get(i), ranges.contains(i));
        }
    }

    @Test
    void enormousQueueNeedsOnlyItsRemainingRanges() {
        OperationRangeSet ranges = new OperationRangeSet(Integer.MAX_VALUE);
        ranges.remove(0, 50_000_000);
        ranges.add(25_000_000, 50_000_000);
        assertEquals(1, ranges.ranges().size());
        assertEquals(25_000_000, ranges.ranges().get(0).first());
        assertEquals(Integer.MAX_VALUE, ranges.ranges().get(0).end());
    }

    @Test
    void monitorSubtractsRecoverableOperationsWithoutChangingEitherSet() {
        var unsettled = new OperationRangeSet(30);
        unsettled.remove(4, 6);
        unsettled.remove(13, 18);
        var recoverable = new OperationRangeSet(30);
        recoverable.remove(0, 2);
        recoverable.remove(9, 11);
        recoverable.remove(20, 30);
        var originalUnsettled = unsettled.ranges();
        var originalRecoverable = recoverable.ranges();

        assertEquals(List.of(new OperationRangeSet.Range(0, 2),
                new OperationRangeSet.Range(9, 11), new OperationRangeSet.Range(20, 30)),
                unsettled.rangesExcluding(recoverable));
        assertEquals(originalUnsettled, unsettled.ranges());
        assertEquals(originalRecoverable, recoverable.ranges());
        assertTrue(unsettled.rangesExcluding(unsettled).isEmpty());
    }

    @Test
    void monitorSubtractsThousandsOfFragmentedRangesInOrder() {
        int total = 16_000;
        var unsettled = new OperationRangeSet(total);
        var recoverable = new OperationRangeSet(total);
        for (int first = 0; first < total; first += 4) {
            unsettled.remove(first + 2, first + 4);
            recoverable.remove(first + 1, first + 3);
        }

        var processing = unsettled.rangesExcluding(recoverable);
        assertEquals(total / 4, processing.size());
        for (int i = 0; i < processing.size(); i++) {
            assertEquals(new OperationRangeSet.Range(4 * i + 1, 4 * i + 2), processing.get(i));
        }
    }
}
