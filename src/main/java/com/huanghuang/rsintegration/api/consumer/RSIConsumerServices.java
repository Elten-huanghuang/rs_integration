package com.huanghuang.rsintegration.api.consumer;

import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.Optional;

@ApiStatus.Internal
public final class RSIConsumerServices {
    private static volatile RSICraftConsumerApi api;

    private RSIConsumerServices() {}

    public static void install(RSICraftConsumerApi implementation) {
        api = Objects.requireNonNull(implementation, "implementation");
    }

    public static void clear() {
        api = null;
    }

    static Optional<RSICraftConsumerApi> current() {
        return Optional.ofNullable(api);
    }
}
