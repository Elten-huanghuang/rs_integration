package com.huanghuang.rsintegration.api;

/**
 * Mixin-injected interface for machine block entities to expose their
 * operational state without reflection.
 *
 * <p>Lives OUTSIDE the mixin package so non-mixin code can safely
 * {@code instanceof}-check without triggering Mixin's illegal-reference guard.</p>
 *
 * <p>When a target mod BE has a corresponding Mixin that {@code @Implements}
 * this interface, {@code rsi$isBusy()} returns the real machine state.
 * Until then, the {@code instanceof} check returns {@code false} and
 * callers fall back to structural checks (chunk loaded, BE present).</p>
 */
public interface RSIMachineAccessor {

    /** True if the machine is currently processing a recipe and should not
     *  receive new work. */
    boolean rsi$isBusy();
}
