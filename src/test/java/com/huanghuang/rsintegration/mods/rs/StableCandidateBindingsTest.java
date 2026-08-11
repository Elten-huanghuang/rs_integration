package com.huanghuang.rsintegration.mods.rs;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StableCandidateBindingsTest {

    @Test
    void replacingEveryTransientIdKeepsStableKeysAlive() {
        StableCandidateBindings bindings = new StableCandidateBindings();
        String apple = "item|minecraft:apple|-";
        String wool = "item|minecraft:white_wool|-";
        bindings.replace(Map.of(UUID.randomUUID(), apple, UUID.randomUUID(), wool));

        StableCandidateBindings.Replacement replacement = bindings.replace(Map.of(
                UUID.randomUUID(), apple, UUID.randomUUID(), wool));

        assertEquals(Set.of(), replacement.removedKeys());
        assertEquals(2, replacement.oldKeys());
        assertEquals(2, replacement.newKeys());
        assertEquals(2, replacement.retainedKeys());
        assertEquals(Set.of(), replacement.addedKeys());
    }

    @Test
    void replacementReportsDisjointStableKeySets() {
        StableCandidateBindings bindings = new StableCandidateBindings();
        bindings.replace(Map.of(UUID.randomUUID(), "item|example:old|-"));

        StableCandidateBindings.Replacement replacement = bindings.replace(
                Map.of(UUID.randomUUID(), "item|example:new|-"));

        assertEquals(Set.of("item|example:old|-"), replacement.removedKeys());
        assertEquals(Set.of("item|example:new|-"), replacement.addedKeys());
        assertEquals(1, replacement.oldKeys());
        assertEquals(1, replacement.newKeys());
        assertEquals(0, replacement.retainedKeys());
    }

    @Test
    void duplicateStableKeysRemainUntilTheirLastTransientIdLeaves() {
        StableCandidateBindings bindings = new StableCandidateBindings();
        String key = "item|minecraft:enchanted_book|abc";
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        bindings.put(first, key);
        bindings.put(second, key);

        assertNull(bindings.remove(first));
        assertEquals(Set.of(second), bindings.ids(key));
        assertEquals(key, bindings.remove(second));
        assertEquals(Set.of(), bindings.ids(key));
    }

    @Test
    void changingOneIdReturnsOnlyItsOrphanedPreviousKey() {
        StableCandidateBindings bindings = new StableCandidateBindings();
        UUID id = UUID.randomUUID();
        String oldKey = "item|example:old|-";
        String newKey = "item|example:new|-";
        bindings.put(id, oldKey);

        assertEquals(oldKey, bindings.put(id, newKey));
        assertEquals(newKey, bindings.key(id));
    }
}
