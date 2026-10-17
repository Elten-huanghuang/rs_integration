package com.huanghuang.rsintegration.magic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MagicMachineIntegrationContractTest {
    private static final Path CATALOG = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "crafting", "plan", "MagicResourceCostCatalog.java");

    @Test
    void onlySoulEntersRsiRecursiveEnergyCatalog() throws Exception {
        assertTrue(MagicResourceKind.SOUL.supportsRsiRecursiveCrafting());
        assertTrue(MagicResourceKind.MANA.supportsRsiRecursiveCrafting());
        assertFalse(MagicResourceKind.SOURCE.supportsRsiRecursiveCrafting());

        String catalog = Files.readString(CATALOG);
        assertTrue(catalog.contains("MagicResourceKind.SOUL"));
        assertFalse(catalog.contains("PlanWarnings.arsSourceCost"));
        assertFalse(catalog.contains("PlanWarnings.botaniaManaCost"));
    }
}
