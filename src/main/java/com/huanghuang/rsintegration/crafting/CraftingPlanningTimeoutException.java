package com.huanghuang.rsintegration.crafting;

/** Terminates a strict preview plan when its server-thread deadline expires. */
public final class CraftingPlanningTimeoutException extends RuntimeException {
    public CraftingPlanningTimeoutException() {
        super("Craft planning exceeded its deadline", null, false, false);
    }
}
