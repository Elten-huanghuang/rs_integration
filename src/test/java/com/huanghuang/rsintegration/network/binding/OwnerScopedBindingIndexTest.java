package com.huanghuang.rsintegration.network.binding;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OwnerScopedBindingIndexTest {
    @Test
    void sameMachineAndBackendRemainIsolatedByPlayer() {
        OwnerScopedBindingIndex<String, String, String> index = new OwnerScopedBindingIndex<>();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();

        index.put("shared-machine", firstPlayer, "storage", "first-network");
        index.put("shared-machine", secondPlayer, "storage", "second-network");

        assertEquals("first-network", index.get("shared-machine", firstPlayer, "storage"));
        assertEquals("second-network", index.get("shared-machine", secondPlayer, "storage"));
    }

    @Test
    void removingOnePlayersBindingDoesNotAffectAnotherPlayer() {
        OwnerScopedBindingIndex<String, String, String> index = new OwnerScopedBindingIndex<>();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        index.put("shared-machine", firstPlayer, "storage", "first-network");
        index.put("shared-machine", secondPlayer, "storage", "second-network");

        index.remove("shared-machine", firstPlayer, "storage");

        assertNull(index.get("shared-machine", firstPlayer, "storage"));
        assertEquals("second-network", index.get("shared-machine", secondPlayer, "storage"));
    }

    @Test
    void backendSlotsAreIndependentAndRebindingReplacesOnlyItsOwnSlot() {
        OwnerScopedBindingIndex<String, String, String> index = new OwnerScopedBindingIndex<>();
        UUID player = UUID.randomUUID();
        index.put("machine", player, "rs", "old-rs-network");
        index.put("machine", player, "bd", "bd-network");

        index.put("machine", player, "rs", "new-rs-network");

        assertEquals("new-rs-network", index.get("machine", player, "rs"));
        assertEquals("bd-network", index.get("machine", player, "bd"));
        assertEquals(2, index.valuesFor("machine", player).size());
    }
}
