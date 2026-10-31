package com.huanghuang.rsintegration.api.storage;

import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.Optional;

/** RSI 内部的服务安装点；功能模块只调用公共 API。 */
@ApiStatus.Internal
public final class RSIStorageServices {
    private static volatile RSIStorageApi api;
    private RSIStorageServices() {}
    public static void install(RSIStorageApi implementation) { api = Objects.requireNonNull(implementation); }
    public static void clear() { api = null; }
    public static Optional<RSIStorageApi> get() { return Optional.ofNullable(api); }
}
