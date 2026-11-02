package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/** 按材料来源索引仍处于 BLOCKED 状态的消费者位置。 */
final class GraphMaterialWakeIndex {
    private final Map<MaterialSource, ConsumerPositions> consumersBySource = new HashMap<>();
    private final Map<NodeId, Integer> positionsByNode = new HashMap<>();
    private final BitSet activePositions = new BitSet();
    private long subscriptionRevision;

    void subscribe(MaterialSource source, NodeId nodeId, int position) {
        Integer existingPosition = positionsByNode.putIfAbsent(nodeId, position);
        if (existingPosition != null && existingPosition != position) {
            throw new IllegalArgumentException("node has multiple topological positions");
        }
        activePositions.set(position);
        consumersBySource.computeIfAbsent(
                source, ignored -> new ConsumerPositions(subscriptionRevision)).add(position);
    }

    void unsubscribe(NodeId nodeId) {
        Integer position = positionsByNode.remove(nodeId);
        if (position != null) {
            activePositions.clear(position);
            subscriptionRevision++;
        }
    }

    void include(MaterialSource source, BitSet candidates) {
        ConsumerPositions positions = consumersBySource.get(source);
        if (positions == null) return;
        positions.includeActive(activePositions, candidates, subscriptionRevision);
        if (positions.isEmpty()) consumersBySource.remove(source, positions);
    }

    private static final class ConsumerPositions {
        private int[] values = new int[4];
        private int size;
        private long compactedRevision;

        private ConsumerPositions(long revision) {
            compactedRevision = revision;
        }

        private void add(int position) {
            if (size > 0 && values[size - 1] == position) return;
            if (size == values.length) {
                int[] expanded = new int[values.length << 1];
                System.arraycopy(values, 0, expanded, 0, size);
                values = expanded;
            }
            values[size++] = position;
        }

        private void includeActive(BitSet activePositions, BitSet candidates, long revision) {
            if (compactedRevision != revision) {
                compactActive(activePositions);
                compactedRevision = revision;
            }
            for (int index = 0; index < size; index++) candidates.set(values[index]);
        }

        private void compactActive(BitSet activePositions) {
            int retained = 0;
            for (int read = 0; read < size; read++) {
                int position = values[read];
                if (!activePositions.get(position)) {
                    continue;
                }
                values[retained++] = position;
            }
            size = retained;
        }

        private boolean isEmpty() {
            return size == 0;
        }
    }
}
