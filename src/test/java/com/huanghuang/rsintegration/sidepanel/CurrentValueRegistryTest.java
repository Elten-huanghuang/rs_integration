package com.huanghuang.rsintegration.sidepanel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentValueRegistryTest {

    @Test
    void staleValueCannotRemoveReplacement() {
        CurrentValueRegistry<String, Object> registry = new CurrentValueRegistry<>();
        Object old = new Object();
        Object replacement = new Object();
        registry.put("player", old);
        registry.put("player", replacement);

        assertFalse(registry.removeCurrent("player", old));
        assertSame(replacement, registry.get("player"));
        assertTrue(registry.removeCurrent("player", replacement));
        assertNull(registry.get("player"));
    }

    @Test
    void removalIsIdempotent() {
        CurrentValueRegistry<String, Object> registry = new CurrentValueRegistry<>();
        Object value = new Object();
        registry.put("player", value);

        assertTrue(registry.removeCurrent("player", value));
        assertFalse(registry.removeCurrent("player", value));
    }
}
