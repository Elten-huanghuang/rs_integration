package com.huanghuang.rsintegration.mods.ironsspellbooks;

import java.lang.reflect.Method;

/** Bridges the post-3.15 reset API without linking it on older Iron builds. */
final class IronSpellRarityCache {
    private static final ClassValue<Method> RESET = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                return type.getMethod("resetRarityWeights");
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Iron spell rarity reset API unavailable", failure);
            }
        }
    };

    private IronSpellRarityCache() {}

    static void resetAll(Object initializationLock, Iterable<?> spells) {
        // Iron initializes weights under SpellRegistry.none(); resetting uses that same lock.
        synchronized (initializationLock) {
            for (Object spell : spells) {
                try {
                    RESET.get(spell.getClass()).invoke(spell);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Could not reset Iron spell rarity cache", failure);
                }
            }
        }
    }
}
