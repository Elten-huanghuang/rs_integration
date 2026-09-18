package com.huanghuang.rsintegration.mods.ironsspellbooks;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
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

        IronSpellRarityCache.resetAll(lock, List.of(first, second), CachedSpell.class);

        assertEquals(4, first.rarity());
        assertEquals(3, second.rarity());
        assertEquals(1, first.resets);
        assertEquals(1, second.resets);
        first.configuredRarity = 2;
        IronSpellRarityCache.resetAll(lock, List.of(first, second), CachedSpell.class);
        assertEquals(2, first.rarity());
        assertEquals(2, first.resets);
    }

    @Test
    void missingResetApiFailsExplicitly() {
        assertThrows(IllegalStateException.class,
                () -> IronSpellRarityCache.resetAll(
                        new Object(), List.of(new Object()), Object.class));
    }

    @Test
    void resetFailureIsNotSilentlyIgnored() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> IronSpellRarityCache.resetAll(
                        new Object(), List.of(new BrokenSpell()), BrokenSpell.class));
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause().getCause());
    }

    @Test
    void resolvesResetFromSafeApiWithoutInspectingConcreteSpellMethods() throws Exception {
        String generatedName = getClass().getPackageName() + ".GeneratedClientLinkedSpell";
        String internalName = generatedName.replace('.', '/');
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null,
                org.objectweb.asm.Type.getInternalName(SafeSpellApi.class), null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                org.objectweb.asm.Type.getInternalName(SafeSpellApi.class), "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        var clientOnly = writer.visitMethod(Opcodes.ACC_PUBLIC, "clientOnly",
                "(Lmissing/client/ClientLevel;)V", null, null);
        clientOnly.visitCode();
        clientOnly.visitInsn(Opcodes.RETURN);
        clientOnly.visitMaxs(0, 2);
        clientOnly.visitEnd();
        writer.visitEnd();

        Class<?> generated = new ByteArrayClassLoader(getClass().getClassLoader())
                .define(generatedName, writer.toByteArray());
        SafeSpellApi spell = (SafeSpellApi) generated.getConstructor().newInstance();

        assertThrows(NoClassDefFoundError.class,
                () -> generated.getMethod("resetRarityWeights"),
                "concrete-class reflection reproduces the dedicated-server failure");
        IronSpellRarityCache.resetAll(new Object(), List.of(spell), SafeSpellApi.class);
        assertEquals(1, spell.resets);
    }

    @Test
    void productionEntryAnchorsLookupToIronApiType() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/huanghuang/rsintegration/mods/"
                + "ironsspellbooks/IronSpellRarityCache.java"));
        String productionEntry = source.substring(
                source.indexOf("static void resetAll(Object initializationLock, Iterable<?> spells)"),
                source.indexOf("static void resetAll(Object initializationLock, Iterable<?> spells, Class<?> apiType)"));
        assertTrue(productionEntry.contains("AbstractSpell.class"));
        assertFalse(productionEntry.contains("spell.getClass()"));
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

    public static class SafeSpellApi {
        int resets;

        public void resetRarityWeights() {
            resets++;
        }
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private ByteArrayClassLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
