package com.huanghuang.rsintegration.mods.ironsspellbooks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IronSpellRarityCacheTest {
    @Test
    void configPublicationClearsWeightsWarmedFromOldDefaults() {
        Object lock = new Object();
        CachedSpell first = new CachedSpell(lock);
        CachedSpell second = new CachedSpell(lock);
        assertEquals(0, first.rarity());
        assertEquals(0, second.rarity());
        first.configuredRarity = 4;
        second.configuredRarity = 3;
        assertEquals(0, first.rarity());

        IronSpellRarityCache.resetAll(lock, List.of(first, second));

        assertEquals(4, first.rarity());
        assertEquals(3, second.rarity());
        assertEquals(1, first.resets);
        assertEquals(1, second.resets);
        first.configuredRarity = 2;
        IronSpellRarityCache.resetAll(lock, List.of(first, second));
        assertEquals(2, first.rarity());
        assertEquals(2, first.resets);
    }

    @Test
    void missingResetApiFailsExplicitly() {
        assertThrows(IllegalStateException.class,
                () -> IronSpellRarityCache.resetAll(new Object(), List.of(new Object())));
    }

    @Test
    void resetFailureIsNotSilentlyIgnored() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> IronSpellRarityCache.resetAll(new Object(), List.of(new BrokenSpell())));
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause().getCause());
    }

    public static class CachedSpell {
        private final Object lock;
        private Integer cachedRarity;
        int configuredRarity;
        int resets;

        CachedSpell(Object lock) {
            this.lock = lock;
        }

        int rarity() {
            synchronized (lock) {
                if (cachedRarity == null) cachedRarity = configuredRarity;
                return cachedRarity;
            }
        }

        public void resetRarityWeights() {
            assertTrue(Thread.holdsLock(lock), "reset must share Iron's initialization lock");
            cachedRarity = null;
            resets++;
        }
    }

    public static class BrokenSpell {
        public void resetRarityWeights() {
            throw new UnsupportedOperationException("broken reset");
        }
    }
}
