package com.huanghuang.rsintegration.crafting.planning;

/** Tick budgets tuned to finish warm-up during JEI loading without long server stalls. */
public final class WarmUpBudgetPolicy {
    private static final Budgets BACKGROUND = new Budgets(4_000_000L, 2_000_000L);
    private static final Budgets REQUEST_WAITING = new Budgets(8_000_000L, 4_000_000L);

    private WarmUpBudgetPolicy() {}

    public static Budgets select(boolean requestWaiting) {
        return requestWaiting ? REQUEST_WAITING : BACKGROUND;
    }

    public record Budgets(long recipeIndexNanos, long recipeGraphNanos) {}
}
