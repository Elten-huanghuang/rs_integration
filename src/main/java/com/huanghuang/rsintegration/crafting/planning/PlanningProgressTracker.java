package com.huanghuang.rsintegration.crafting.planning;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/** Client-side state for the single current plan-preview request. */
@OnlyIn(Dist.CLIENT)
public final class PlanningProgressTracker {
    private static final long TERMINAL_VISIBLE_MS = 3_000L;
    private static final long LOCAL_TIMEOUT_MS = 60_000L;
    private static PlanningProgressSnapshot current;
    private static long localStartedAt;
    private static long terminalSince;

    private PlanningProgressTracker() {}

    public static void start(long requestId, ResourceLocation recipeId) {
        if (requestId <= 0L || recipeId == null) return;
        current = new PlanningProgressSnapshot(requestId, 0L, recipeId,
                PlanningProgressSnapshot.State.ACCEPTED,
                PlanningProgressSnapshot.Phase.ACCEPTING, 0L, Component.empty());
        localStartedAt = System.currentTimeMillis();
        terminalSince = 0L;
    }

    public static void update(PlanningProgressSnapshot snapshot) {
        if (snapshot == null || snapshot.requestId() <= 0L) return;
        // Every normal preview starts locally before its packet is sent. Requiring
        // that local entry prevents a very late packet from resurrecting an expired card.
        if (current == null || snapshot.requestId() != current.requestId()) return;
        if (current != null && current.requestGeneration() > 0L
                && snapshot.requestGeneration() > 0L
                && snapshot.requestGeneration() < current.requestGeneration()) return;
        current = snapshot;
        if (snapshot.terminal()) terminalSince = System.currentTimeMillis();
    }

    @Nullable
    public static PlanningProgressSnapshot current() {
        expire();
        return current;
    }

    public static long elapsedMillis() {
        PlanningProgressSnapshot snapshot = current();
        if (snapshot == null) return 0L;
        long local = Math.max(0L, System.currentTimeMillis() - localStartedAt);
        return snapshot.terminal() ? snapshot.elapsedMillis()
                : Math.max(snapshot.elapsedMillis(), local);
    }

    public static void clear() {
        current = null;
        localStartedAt = 0L;
        terminalSince = 0L;
    }

    private static void expire() {
        if (current == null) return;
        long now = System.currentTimeMillis();
        if (current.terminal() && terminalSince > 0L
                && now - terminalSince >= TERMINAL_VISIBLE_MS) {
            clear();
        } else if (!current.terminal() && localStartedAt > 0L
                && now - localStartedAt >= LOCAL_TIMEOUT_MS) {
            current = new PlanningProgressSnapshot(current.requestId(),
                    current.requestGeneration(), current.recipeId(),
                    PlanningProgressSnapshot.State.TIMED_OUT, current.phase(),
                    now - localStartedAt,
                    Component.translatable("rsi.planning.detail.no_update"));
            terminalSince = now;
        }
    }
}
