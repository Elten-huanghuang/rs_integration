package com.huanghuang.rsintegration.crafting;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveCraftRegistryTest {

    @Test
    void addGetAndRemoveUseStableCraftIdentity() {
        ActiveCraftRegistry<UUID, Object> registry = new ActiveCraftRegistry<>();
        UUID craftId = UUID.randomUUID();
        Object craft = new Object();

        assertTrue(registry.add(craftId, craft));
        assertSame(craft, registry.get(craftId));
        assertEquals(1, registry.size());
        assertTrue(registry.remove(craftId));
        assertNull(registry.get(craftId));
        assertEquals(0, registry.size());
    }

    @Test
    void duplicateCraftIdentityIsRejectedWithoutReplacement() {
        ActiveCraftRegistry<UUID, Object> registry = new ActiveCraftRegistry<>();
        UUID craftId = UUID.randomUUID();
        Object first = new Object();

        assertTrue(registry.add(craftId, first));
        assertFalse(registry.add(craftId, new Object()));
        assertSame(first, registry.get(craftId));
    }

    @Test
    void removalIsIdempotent() {
        ActiveCraftRegistry<UUID, Object> registry = new ActiveCraftRegistry<>();
        UUID craftId = UUID.randomUUID();
        registry.add(craftId, new Object());

        assertTrue(registry.remove(craftId));
        assertFalse(registry.remove(craftId));
    }

    @Test
    void snapshotPreservesSubmissionOrder() {
        ActiveCraftRegistry<UUID, String> registry = new ActiveCraftRegistry<>();
        registry.add(UUID.randomUUID(), "first");
        registry.add(UUID.randomUUID(), "second");

        assertEquals(List.of("first", "second"), registry.snapshot());
    }
}
