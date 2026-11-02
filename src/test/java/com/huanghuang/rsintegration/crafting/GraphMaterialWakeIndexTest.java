package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphMaterialWakeIndexTest {
    private static final MaterialSource FIRST_SOURCE =
            new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 0));
    private static final MaterialSource SECOND_SOURCE =
            new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(1), 0));

    @Test
    void readyConsumersAreRemovedFromEachSourceAfterLazyCompaction() {
        GraphMaterialWakeIndex index = new GraphMaterialWakeIndex();
        NodeId active = new NodeId(10);
        NodeId ready = new NodeId(20);
        NodeId cancelled = new NodeId(30);
        index.subscribe(FIRST_SOURCE, active, 2);
        index.subscribe(FIRST_SOURCE, ready, 5);
        index.subscribe(SECOND_SOURCE, ready, 5);
        index.subscribe(FIRST_SOURCE, cancelled, 8);
        index.subscribe(FIRST_SOURCE, active, 2);

        BitSet candidates = new BitSet();
        index.include(FIRST_SOURCE, candidates);
        assertEquals(bits(2, 5, 8), candidates);

        index.unsubscribe(ready);
        index.unsubscribe(cancelled);
        candidates.clear();
        index.include(FIRST_SOURCE, candidates);
        assertEquals(bits(2), candidates);

        candidates.clear();
        index.include(FIRST_SOURCE, candidates);
        assertEquals(bits(2), candidates);

        candidates.clear();
        index.include(SECOND_SOURCE, candidates);
        assertEquals(new BitSet(), candidates);
    }

    @Test
    void blockedConsumersRemainSubscribedUntilExplicitlyUnsubscribed() {
        GraphMaterialWakeIndex index = new GraphMaterialWakeIndex();
        NodeId consumer = new NodeId(7);
        index.subscribe(FIRST_SOURCE, consumer, 4);
        index.subscribe(SECOND_SOURCE, consumer, 4);

        BitSet candidates = new BitSet();
        index.include(FIRST_SOURCE, candidates);
        assertEquals(bits(4), candidates);
        candidates.clear();
        index.include(SECOND_SOURCE, candidates);
        assertEquals(bits(4), candidates);
    }

    private static BitSet bits(int... positions) {
        BitSet result = new BitSet();
        for (int position : positions) result.set(position);
        return result;
    }
}
