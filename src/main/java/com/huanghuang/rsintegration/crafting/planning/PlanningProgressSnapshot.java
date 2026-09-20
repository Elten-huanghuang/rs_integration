package com.huanghuang.rsintegration.crafting.planning;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Immutable, request-correlated planning progress sent to the preview client. */
public record PlanningProgressSnapshot(long requestId, long requestGeneration,
                                       ResourceLocation recipeId, State state, Phase phase,
                                       long elapsedMillis, Component detail) {
    public PlanningProgressSnapshot {
        if (requestId < 0L) throw new IllegalArgumentException("requestId must be non-negative");
        if (requestGeneration < 0L) {
            throw new IllegalArgumentException("requestGeneration must be non-negative");
        }
        if (recipeId == null) recipeId = new ResourceLocation("minecraft", "air");
        if (state == null) state = State.ACCEPTED;
        if (phase == null) phase = Phase.ACCEPTING;
        elapsedMillis = Math.max(0L, elapsedMillis);
        if (detail == null) detail = Component.empty();
    }

    public boolean terminal() {
        return state == State.SUCCEEDED || state == State.FAILED
                || state == State.TIMED_OUT || state == State.CANCELLED
                || state == State.STALE;
    }

    public enum State {
        ACCEPTED,
        WARMING_UP,
        QUEUED,
        RUNNING,
        FINALIZING,
        SUCCEEDED,
        FAILED,
        TIMED_OUT,
        CANCELLED,
        STALE
    }

    public enum Phase {
        ACCEPTING,
        CATALOG,
        PREPARING,
        DEPENDENCIES,
        DEMAND_TREE,
        INVENTORY,
        SPECIAL_RECIPE,
        SEARCH,
        FINALIZING,
        COMPLETE
    }
}
