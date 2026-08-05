package com.huanghuang.rsintegration.crafting.planning;

/** Tick budgets that preserve server tick headroom while recipe warm-up is running. */
public final class WarmUpBudgetPolicy {
    private static final Budgets BACKGROUND = new Budgets(4_000_000L, 2_000_000L);
    private static final Budgets REQUEST_WAITING = new Budgets(8_000_000L, 4_000_000L);
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
