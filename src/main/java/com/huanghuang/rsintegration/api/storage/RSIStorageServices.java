package com.huanghuang.rsintegration.api.storage;

import java.util.Optional;

/** RSI-owned service installation point; feature code only calls this public API. */
@org.jetbrains.annotations.ApiStatus.Internal
public final class RSIStorageServices {
    private static volatile RSIStorageApi api;
    private RSIStorageServices() {}
    public static void install(RSIStorageApi implementation) { api = java.util.Objects.requireNonNull(implementation); }
    public static void clear() { api = null; }
    public static Optional<RSIStorageApi> get() { return Optional.ofNullable(api); }
}
