package com.huanghuang.rsintegration.mods.ironsspellbooks;

import io.redspace.ironsspellbooks.api.spells.AbstractSpell;

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
        // Resolving against a concrete addon spell scans all of that class's
        // public method signatures. Some contain client-only types, which the
        // dedicated-server dist cleaner rejects before login can complete.
        resetAll(initializationLock, spells, AbstractSpell.class);
    }

    static void resetAll(Object initializationLock, Iterable<?> spells, Class<?> apiType) {
        Method reset = RESET.get(apiType);
        // Iron initializes weights under SpellRegistry.none(); resetting uses that same lock.
        synchronized (initializationLock) {
            for (Object spell : spells) {
                try {
                    reset.invoke(spell);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Could not reset Iron spell rarity cache", failure);
                }
            }
        }
    }
}
