package com.huanghuang.rsintegration.storage.rs;

/** Internal signal that the referenced RS network is no longer the current runnable network. */
final class RefinedStorageUnavailableException extends IllegalStateException {
    RefinedStorageUnavailableException() {
        super("Refined Storage network is unavailable");
    }
}
