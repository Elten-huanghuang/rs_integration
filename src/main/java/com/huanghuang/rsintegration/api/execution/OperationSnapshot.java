package com.huanghuang.rsintegration.api.execution;

import net.minecraft.nbt.CompoundTag;

public record OperationSnapshot(
        LifecyclePhase phase,
        long collectionSequence,
        CompoundTag state
) {
    public OperationSnapshot {
        if (phase == null) {
            throw new IllegalArgumentException("snapshot phase is required");
        }
        if (collectionSequence < 0L) {
            throw new IllegalArgumentException(
                    "snapshot collectionSequence must be non-negative");
        }
        state = state == null ? new CompoundTag() : state.copy();
    }

    @Override
    public CompoundTag state() {
        return state.copy();
    }
}
