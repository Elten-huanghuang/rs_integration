package com.huanghuang.rsintegration.mods.goety;

/** Shared safety classification for Goety Dark Altar rituals. */
public final class GoetyRitualPolicy {
    public enum Execution {
        AUTOMATIC,
        MANUAL_CONFIRMATION,
        UNSUPPORTED
    }

    private GoetyRitualPolicy() {}

    public static Execution classify(Object recipe, Object ritual) {
        if (ritual == null) return Execution.UNSUPPORTED;
        String name = ritual.getClass().getSimpleName();
        if (name.equals("TeleportRitual")) return Execution.UNSUPPORTED;
        if (name.equals("SummonRitual") || name.equals("ConvertRitual")) {
            return Execution.MANUAL_CONFIRMATION;
        }
        try {
            Object value = recipe.getClass().getMethod("requiresSacrifice").invoke(recipe);
            return Boolean.TRUE.equals(value)
                    ? Execution.MANUAL_CONFIRMATION
                    : Execution.AUTOMATIC;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            // Unknown ritual variants must never be started automatically.
            return Execution.MANUAL_CONFIRMATION;
        }
    }
}
