package com.huanghuang.rsintegration.crafting.planning;

/** Tick budgets that preserve server tick headroom while recipe warm-up is running. */
public final class WarmUpBudgetPolicy {
    // The index is built once per recipe-manager revision.  Four milliseconds per
    // tick left large worlds warming for more than a minute, so the first plan
    // request was commonly held behind an unfinished index.  Keep the combined
    // background budget below one normal 45 ms server tick while giving the
    // incremental builder enough throughput to finish during world startup.
    private static final Budgets BACKGROUND = new Budgets(16_000_000L, 8_000_000L);
    private static final Budgets REQUEST_WAITING = new Budgets(24_000_000L, 12_000_000L);
    private static final long TARGET_TICK_NANOS = 45_000_000L;
    private static final long MINIMUM_PROGRESS_NANOS = 250_000L;
    private static volatile long tickStartedNanos;

    private WarmUpBudgetPolicy() {}

    public static Budgets select(boolean requestWaiting) {
        return requestWaiting ? REQUEST_WAITING : BACKGROUND;
    }

    public static Budgets select(boolean requestWaiting, long elapsedTickNanos) {
        Budgets desired = select(requestWaiting);
        long headroom = Math.max(MINIMUM_PROGRESS_NANOS,
                TARGET_TICK_NANOS - Math.max(0L, elapsedTickNanos));
        return new Budgets(Math.min(desired.recipeIndexNanos(), headroom),
                Math.min(desired.recipeGraphNanos(), headroom));
    }

    public static void beginTick() {
        tickStartedNanos = System.nanoTime();
    }

    public static Budgets selectForCurrentTick(boolean requestWaiting) {
        long started = tickStartedNanos;
        long elapsed = started == 0L ? 0L : Math.max(0L, System.nanoTime() - started);
        return select(requestWaiting, elapsed);
    }

    public record Budgets(long recipeIndexNanos, long recipeGraphNanos) {}
}
