package com.huanghuang.rsintegration.unifiedgrid;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 客户端状态机不依赖屏幕：快照只有收齐后才替换对应类别。 */
public final class UnifiedGridState {
    public enum Result { IGNORED, STAGED, RESET, DELTA, RESYNC }
    private final EnumMap<GridResourceKind, State> states = new EnumMap<>(GridResourceKind.class);
    private UUID session;
    private final Set<UUID> retiredSessions = new HashSet<>();

    public UnifiedGridState() {
        for (GridResourceKind kind : GridResourceKind.values()) states.put(kind, new State());
    }

    public UUID session() { return session; }
    public int epoch(GridResourceKind kind) { return states.get(kind).epoch; }
    public boolean needsResync(GridResourceKind kind) { return states.get(kind).broken; }
    public boolean ready(GridResourceKind kind) {
        State state = states.get(kind);
        return state.epoch > 0 && state.pending == null && !state.broken;
    }
    public Collection<UnifiedGridEntry> entries(GridResourceKind kind) { return states.get(kind).visible.values(); }
    public UnifiedGridEntry get(GridResourceKind kind, int serial) { return states.get(kind).visible.get(serial); }

    public Result apply(UnifiedGridUpdatePacket packet, List<UnifiedGridEntry> updates) {
        if (!packet.enabled() || packet.epoch() <= 0 || packet.sequence() < 0) return Result.IGNORED;
        if (!packet.session().equals(session)) {
            if (retiredSessions.contains(packet.session()) || !packet.begin() || packet.sequence() != 0) return Result.IGNORED;
            if (session != null) retiredSessions.add(session);
            session = packet.session();
            for (State state : states.values()) state.clear();
        }
        State state = states.get(packet.kind());
        if (packet.begin()) {
            if (packet.sequence() != 0 || packet.epoch() <= state.epoch) return Result.IGNORED;
            state.epoch = packet.epoch();
            state.expected = 0;
            state.broken = false;
            state.pending = new Int2ObjectLinkedOpenHashMap<>();
        }
        if (packet.epoch() != state.epoch || state.broken) return Result.IGNORED;
        if (packet.sequence() < state.expected) return Result.IGNORED;
        if (packet.sequence() != state.expected) {
            state.broken = true;
            state.pending = null;
            return Result.RESYNC;
        }
        Int2ObjectLinkedOpenHashMap<UnifiedGridEntry> target = state.pending == null ? state.visible : state.pending;
        // 先验证整包，避免一个未知增量让前半包已改变、后半包被丢弃。
        for (UnifiedGridEntry update : updates) {
            if (!update.metadata() && !target.containsKey(update.serial())) {
                state.broken = true;
                state.pending = null;
                return Result.RESYNC;
            }
        }
        for (UnifiedGridEntry update : updates) {
            if (update.removed()) target.remove(update.serial());
            else if (update.metadata()) target.put(update.serial(), update);
            else {
                UnifiedGridEntry old = target.get(update.serial());
                target.put(update.serial(), new UnifiedGridEntry(old.serial(), update.amount(), true,
                        old.storedId(), old.craftableId(), old.template(), update.tracker()));
            }
        }
        state.expected++;
        if (packet.end()) {
            if (state.pending == null) {
                state.broken = true;
                return Result.RESYNC;
            }
            state.visible = state.pending;
            state.pending = null;
            return Result.RESET;
        }
        return state.pending == null ? Result.DELTA : Result.STAGED;
    }

    private static final class State {
        private int epoch;
        private int expected;
        private boolean broken;
        private Int2ObjectLinkedOpenHashMap<UnifiedGridEntry> visible = new Int2ObjectLinkedOpenHashMap<>();
        private Int2ObjectLinkedOpenHashMap<UnifiedGridEntry> pending;
        private void clear() {
            epoch = expected = 0;
            broken = false;
            visible.clear();
            pending = null;
        }
    }
}
