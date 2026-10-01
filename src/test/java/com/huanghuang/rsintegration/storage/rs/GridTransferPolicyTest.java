package com.huanghuang.rsintegration.storage.rs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GridTransferPolicyTest {
    @Test
    void onlyExpandsFullSnapshotsAndNeverReducesExistingLimits() {
        String prefix = "com.refinedmods.refinedstorage.network.grid.";
        for (String name : new String[]{"GridItemUpdateMessage", "GridFluidUpdateMessage",
                "PortableGridItemUpdateMessage", "PortableGridFluidUpdateMessage"}) {
            assertEquals(100, GridTransferPolicy.limit(prefix + name, 10, true, 100));
            assertEquals(10, GridTransferPolicy.limit(prefix + name, 10, false, 100));
            assertEquals(300, GridTransferPolicy.limit(prefix + name, 300, true, 100));
            assertEquals(2000, GridTransferPolicy.limit(prefix + name, 10, true, Integer.MAX_VALUE));
            assertEquals(10, GridTransferPolicy.limit(prefix + name, 10, true, -1));
        }
        assertEquals(10, GridTransferPolicy.limit(prefix + "GridItemExtractMessage", 10, true, 100));
        assertEquals(10, GridTransferPolicy.limit(prefix + "GridItemDeltaMessage", 10, true, 100));
    }
}
