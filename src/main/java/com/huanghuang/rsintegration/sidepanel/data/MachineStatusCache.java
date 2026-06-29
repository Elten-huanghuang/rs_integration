package com.huanghuang.rsintegration.sidepanel.data;

import com.huanghuang.rsintegration.machine.MachineState;
import com.huanghuang.rsintegration.machine.MachineStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of live machine status, updated by {@code MachineStatusDeltaPacket}.
 * Keyed by dimension + pos so status can be looked up for any binding.
 */
public final class MachineStatusCache {

    private static final MachineStatusCache INSTANCE = new MachineStatusCache();

    /** "{dim}:{x},{y},{z}" → MachineStatus */
    private final Map<String, MachineStatus> statusMap = new ConcurrentHashMap<>();

    private MachineStatusCache() {}

    public static MachineStatusCache getInstance() { return INSTANCE; }

    // ── Update ───────────────────────────────────────────────────

    public void put(ResourceLocation dim, BlockPos pos, MachineStatus status) {
        if (dim == null || pos == null) return;
        statusMap.put(key(dim, pos), status);
    }

    public void remove(ResourceLocation dim, BlockPos pos) {
        if (dim == null || pos == null) return;
        statusMap.remove(key(dim, pos));
    }

    // ── Query ────────────────────────────────────────────────────

    public MachineStatus get(ResourceLocation dim, BlockPos pos) {
        if (dim == null || pos == null) return MachineStatus.UNKNOWN;
        return statusMap.getOrDefault(key(dim, pos), MachineStatus.UNKNOWN);
    }

    public MachineStatus get(BindingInfo info) {
        if (info == null) return MachineStatus.UNKNOWN;
        return get(info.dim(), info.pos());
    }

    /** True if any cached machine has output ready for collection. */
    public boolean hasAnyOutput() {
        for (MachineStatus s : statusMap.values()) {
            if (s.state() == MachineState.HAS_OUTPUT) return true;
        }
        return false;
    }

    /** Clear all cached status. */
    public void clear() {
        statusMap.clear();
    }

    // ── Internal ─────────────────────────────────────────────────

    private static String key(ResourceLocation dim, BlockPos pos) {
        return dim.toString() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
