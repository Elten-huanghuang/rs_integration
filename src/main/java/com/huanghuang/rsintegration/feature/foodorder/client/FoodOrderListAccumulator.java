package com.huanghuang.rsintegration.feature.foodorder.client;

import com.huanghuang.rsintegration.feature.foodorder.FoodOrderEntry;
import com.huanghuang.rsintegration.feature.foodorder.FoodOrderSnapshot;
import com.huanghuang.rsintegration.feature.foodorder.network.FoodOrderListPacket;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

/** 收齐同一版本的目录再交给界面，避免分片互相覆盖或旧包回写。 */
final class FoodOrderListAccumulator {
    private long epoch = Long.MIN_VALUE;
    private List<FoodOrderEntry> entries = List.of();
    private final BitSet received = new BitSet();
    private int receivedCount;
    private boolean complete;

    @Nullable
    FoodOrderSnapshot accept(FoodOrderListPacket packet) {
        if (packet.epoch() < epoch) {
            return null;
        }
        if (packet.epoch() > epoch) {
            epoch = packet.epoch();
            entries = new ArrayList<>(Collections.nCopies(packet.totalEntries(), null));
            received.clear();
            receivedCount = 0;
            complete = false;
        }
        if (complete || entries.size() != packet.totalEntries()) {
            return null;
        }
        for (int index = 0; index < packet.entries().size(); index++) {
            int target = packet.offset() + index;
            if (!received.get(target)) {
                entries.set(target, packet.entries().get(index));
                received.set(target);
                receivedCount++;
            }
        }
        if (receivedCount != entries.size()) {
            return null;
        }
        complete = true;
        return new FoodOrderSnapshot(epoch, entries, packet.sources(), packet.totalUneaten(),
                packet.totalOrderable(), packet.indexing(), packet.partial());
    }
}
