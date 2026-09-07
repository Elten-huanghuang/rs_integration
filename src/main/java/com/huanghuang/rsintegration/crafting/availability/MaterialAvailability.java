package com.huanghuang.rsintegration.crafting.availability;

/** Availability of the item inputs for one recipe execution, not machine readiness. */
public enum MaterialAvailability {
    UNKNOWN, MISSING, READY;

    public String translationKey() {
        return "rsi.recipe.materials." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
