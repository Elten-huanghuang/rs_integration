package com.huanghuang.rsintegration.crafting.loadbalancer;

import org.junit.jupiter.api.Test;
import java.util.BitSet;
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
}
