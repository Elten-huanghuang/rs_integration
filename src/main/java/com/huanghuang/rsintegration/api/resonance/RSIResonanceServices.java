package com.huanghuang.rsintegration.api.resonance;

import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.Optional;

@ApiStatus.Internal
public final class RSIResonanceServices {
    private static volatile RSIResonanceApi api;

    private RSIResonanceServices() {}

    public static void install(RSIResonanceApi implementation) {
        api = Objects.requireNonNull(implementation, "implementation");
    }

    public static void clear() {
        api = null;
    }

    static Optional<RSIResonanceApi> current() {
        return Optional.ofNullable(api);
    }
}
