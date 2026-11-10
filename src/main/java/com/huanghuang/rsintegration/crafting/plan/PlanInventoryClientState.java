package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 一个窗口的库存视图；旧窗口、旧请求、重复包和不完整分片均不能改写当前材料。 */
final class PlanInventoryClientState {
    final long requestId;
    final long watchId;
    private final List<IngredientKey> keys;
    private final Map<IngredientKey, PlanResponse.Availability> materials;
    private final PlanResponse view;
    private final Map<Integer, PlanInventoryUpdatePacket> parts = new HashMap<>();
    private long sequence;
    private boolean resync;
    private PreviewInventoryModel.Status status;
    private List<IngredientKey> changed = List.of();

    PlanInventoryClientState(long requestId, long watchId, PlanResponse plan) {
        this.requestId = requestId;
        this.watchId = watchId;
        keys = List.copyOf(plan.materials().keySet());
        materials = new LinkedHashMap<>(plan.materials());
        view = plan.withMaterialAvailability(Collections.unmodifiableMap(materials));
    }

    boolean accept(PlanInventoryUpdatePacket packet) {
        if (packet.requestId() != requestId || packet.watchId() != watchId || packet.sequence() <= sequence || resync) return false;
        if (packet.sequence() != sequence + 1 || (sequence == 0 && !packet.full())) return fail();
        if (!parts.isEmpty()) {
            var first = parts.values().iterator().next();
            if (first.parts() != packet.parts() || first.full() != packet.full() || first.status() != packet.status()) return fail();
        }
        var previous = parts.putIfAbsent(packet.part(), packet);
        if (previous != null && !previous.equals(packet)) return fail();
        if (parts.size() != packet.parts()) return false;
        List<PlanInventoryUpdatePacket.Entry> entries = new ArrayList<>();
        Set<Integer> rows = new HashSet<>();
        for (int i = 0; i < packet.parts(); i++) {
            for (var entry : parts.get(i).entries()) {
                if (entry.row() >= keys.size() || !rows.add(entry.row())) return fail();
                entries.add(entry);
            }
        }
        if (packet.full() && packet.status() != PreviewInventoryModel.Status.UNAVAILABLE && rows.size() != keys.size()) return fail();
        List<IngredientKey> updates = new ArrayList<>();
        for (var entry : entries) {
            IngredientKey key = keys.get(entry.row());
            var before = materials.get(key);
            var after = new PlanResponse.Availability(before.needed(), entry.available(), entry.missing());
            if (!before.equals(after)) {
                materials.put(key, after);
                updates.add(key);
            }
        }
        sequence = packet.sequence();
        status = packet.status();
        changed = List.copyOf(updates);
        parts.clear();
        return true;
    }

    private boolean fail() {
        parts.clear();
        resync = true;
        status = PreviewInventoryModel.Status.UNAVAILABLE;
        return false;
    }

    PlanResponse view() { return view; }
    PreviewInventoryModel.Status status() { return status; }
    List<IngredientKey> changed() { return changed; }
    boolean needsResync() { return resync; }
}
