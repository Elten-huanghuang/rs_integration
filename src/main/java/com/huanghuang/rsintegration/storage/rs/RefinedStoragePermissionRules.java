package com.huanghuang.rsintegration.storage.rs;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Pure permission composition kept outside the native RS boundary. */
final class RefinedStoragePermissionRules {
    private RefinedStoragePermissionRules() {}

    static boolean canView(BooleanSupplier canInsert, BooleanSupplier canExtract,
                           BooleanSupplier canAutocraft) {
        Objects.requireNonNull(canInsert, "canInsert");
        Objects.requireNonNull(canExtract, "canExtract");
        Objects.requireNonNull(canAutocraft, "canAutocraft");
        return canInsert.getAsBoolean() || canExtract.getAsBoolean()
                || canAutocraft.getAsBoolean();
    }
}
