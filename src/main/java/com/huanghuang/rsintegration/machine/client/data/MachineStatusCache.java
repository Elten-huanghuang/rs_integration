package com.huanghuang.rsintegration.machine.client.data;

import com.huanghuang.rsintegration.machine.MachineState;
import com.huanghuang.rsintegration.machine.MachineStatus;
import com.huanghuang.rsintegration.machine.MachineStatusKey;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of live machine status, updated by {@code MachineStatusDeltaPacket}.
 * Keyed by dimension + pos so status can be looked up for any binding.
 */
public final class MachineStatusCache {

    private static final MachineStatusCache INSTANCE = new MachineStatusCache();

    private final Map<MachineStatusKey, MachineStatus> statusMap =
            new ConcurrentHashMap<>();
    private volatile long lastSequence = -1L;

    private MachineStatusCache() {}

    public static MachineStatusCache getInstance() { return INSTANCE; }

    // ── Update ───────────────────────────────────────────────────

    public void put(ResourceLocation dim, BlockPos pos, MachineStatus status) {
        if (dim == null || pos == null) return;
        statusMap.put(new MachineStatusKey(dim, pos), status);
    }

    public void remove(ResourceLocation dim, BlockPos pos) {
        if (dim == null || pos == null) return;
        statusMap.remove(new MachineStatusKey(dim, pos));
    }

    // ── Query ────────────────────────────────────────────────────

    public MachineStatus get(ResourceLocation dim, BlockPos pos) {
        if (dim == null || pos == null) return MachineStatus.UNKNOWN;
        return statusMap.getOrDefault(
                new MachineStatusKey(dim, pos), MachineStatus.UNKNOWN);
    }

    public MachineStatus get(BindingInfo info) {
        if (info == null) return MachineStatus.UNKNOWN;
        return statusMap.getOrDefault(
                MachineStatusKey.of(info), MachineStatus.UNKNOWN);
    }

    /** True if any cached machine has output ready for collection. */
    public boolean hasAnyOutput() {
        for (MachineStatus s : statusMap.values()) {
            if (s.state() == MachineState.HAS_OUTPUT) return true;
        }
        return false;
    }

    public boolean acceptSequence(long sequence) {
        if (sequence > 0 && sequence <= lastSequence) return false;
        if (sequence > 0) lastSequence = sequence;
        return true;
    }

    public void clearDimension(ResourceLocation dim) {
        if (dim == null) return;
        for (MachineStatusKey key : new ArrayList<>(statusMap.keySet())) {
            if (key.dimension().equals(dim)) statusMap.remove(key);
        }
    }

    public void clear() {
        statusMap.clear();
        lastSequence = -1L;
    }

}
