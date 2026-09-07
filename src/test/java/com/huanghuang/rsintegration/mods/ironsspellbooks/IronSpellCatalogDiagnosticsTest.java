package com.huanghuang.rsintegration.mods.ironsspellbooks;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IronSpellCatalogDiagnosticsTest extends com.huanghuang.rsintegration.testutil.BootstrapTest {
    @Test
    void reflectedAndNativeNegativeIndicesDoNotDiscardOtherSpellLevels() {
        IronSpellCatalogDiagnostics diagnostics = new IronSpellCatalogDiagnostics();
        List<Integer> retained = new ArrayList<>();
        for (int level = 1; level <= 5; level++) {
            int candidate = level;
            Integer recipe = diagnostics.withFallback("test:spell", level, () -> {
                if (candidate == 2) throw new InvocationTargetException(
                        new ArrayIndexOutOfBoundsException("Index -2 out of bounds for length 8"));
                return candidate;
            }, () -> {
                throw new ArrayIndexOutOfBoundsException("Index -2 out of bounds for length 8");
            });
            if (recipe != null) retained.add(recipe);
        }
        assertEquals(List.of(1, 3, 4, 5), retained);
        assertTrue(diagnostics.summary().contains("arcane_anvil_jei=1"));
        assertTrue(diagnostics.summary().contains("arcane_anvil_native=1"));
        assertTrue(diagnostics.summary().contains("spell=test:spell level=2"));
        assertTrue(diagnostics.summary().contains("ArrayIndexOutOfBoundsException"));
        assertFalse(diagnostics.summary().contains("InvocationTargetException"));
    }

    @Test
    void onlyFailedJeiVariantsUseNativeFallback() {
        IronSpellCatalogDiagnostics diagnostics = new IronSpellCatalogDiagnostics();
        AtomicInteger fallbacks = new AtomicInteger();
        assertEquals("jei", diagnostics.withFallback("test:spell", 2,
                () -> "jei", () -> { fallbacks.incrementAndGet(); return "native"; }));
        assertNull(diagnostics.withFallback("test:spell", 3,
                () -> null, () -> { fallbacks.incrementAndGet(); return "invented"; }));
        assertEquals(0, fallbacks.get());
        assertEquals("native", diagnostics.withFallback("test:spell", 4,
                () -> { throw new NoSuchMethodError("old API"); },
                () -> { fallbacks.incrementAndGet(); return "native"; }));
        assertEquals(1, fallbacks.get());
    }

    @Test
    void dedicatedServerWithoutJeiStillUsesNativeRecipes() {
        assertEquals("native", new IronSpellCatalogDiagnostics().withFallback(
                "test:spell", 2, null, () -> "native"));
    }

    @Test
    void unreadableDataIsUnknownNotAReplacementRarityOrMaterial() {
        var result = new IronSpellCatalogDiagnostics().read("rarity", "test:spell", 2,
                () -> { throw new ArrayIndexOutOfBoundsException(-3); });
        assertTrue(result.failed());
        assertNull(result.value());
    }

    @Test
    void repeatedFailedFingerprintIsStableAndRecoveryChangesIt() {
        long first = failedFingerprint();
        assertEquals(first, failedFingerprint());
        assertNotEquals(first, IronSpellBooksRecipeCatalog.fingerprintProbe(123,
                new IronSpellCatalogDiagnostics(), "rarity", "test:spell", 2, () -> 0));
        assertNotEquals(first, IronSpellBooksRecipeCatalog.fingerprintProbe(123,
                new IronSpellCatalogDiagnostics(), "rarity", "test:spell", 2, () -> -1));
    }

    private static long failedFingerprint() {
        return IronSpellBooksRecipeCatalog.fingerprintProbe(123, new IronSpellCatalogDiagnostics(),
                "rarity", "test:spell", 2, () -> { throw new ArrayIndexOutOfBoundsException(-2); });
    }

    @Test
    void changesToValidRarityAndInkMappingRemainDetectable() {
        long first = IronSpellBooksRecipeCatalog.fingerprintProbe(123, new IronSpellCatalogDiagnostics(),
                "rarity", "test:spell", 2, () -> 1);
        long second = IronSpellBooksRecipeCatalog.fingerprintProbe(123, new IronSpellCatalogDiagnostics(),
                "rarity", "test:spell", 2, () -> 2);
        assertNotEquals(first, second);
    }

    @Test
    void summaryBoundsSamplesAndSingleLineDetailsWithoutDroppingCounts() {
        IronSpellCatalogDiagnostics diagnostics = new IronSpellCatalogDiagnostics();
        for (int level = 0; level < 500; level++) {
            diagnostics.read("rarity", "test:spell", level, () -> {
                throw new IllegalArgumentException("line\n" + "x".repeat(2000));
            });
        }
        String summary = diagnostics.summary();
        assertTrue(summary.contains("rarity=500"));
        assertEquals(8, summary.split("spell=test:spell", -1).length - 1);
        assertTrue(summary.contains("[truncated]"));
        assertFalse(summary.contains("\n"));
        assertTrue(summary.length() < 4000);
    }

    @Test
    void fatalVmErrorsAreNotDisguisedAsBadRecipes() {
        IronSpellCatalogDiagnostics diagnostics = new IronSpellCatalogDiagnostics();
        assertThrows(OutOfMemoryError.class, () -> diagnostics.read("rarity", "test:spell", 2,
                () -> { throw new OutOfMemoryError("test"); }));
        assertThrows(OutOfMemoryError.class, () -> diagnostics.read("rarity", "test:spell", 2,
                () -> { throw new InvocationTargetException(new OutOfMemoryError("test")); }));
        assertFalse(diagnostics.hasFailures());
    }
}
