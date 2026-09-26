package com.huanghuang.rsintegration.crafting.availability;

import java.util.Locale;

/** Availability of the item inputs for one recipe execution, not machine readiness. */
public enum MaterialAvailability {
    UNKNOWN, MISSING, READY;

    public String translationKey() {
        return "rsi.recipe.materials." + name().toLowerCase(Locale.ROOT);
    }
}
