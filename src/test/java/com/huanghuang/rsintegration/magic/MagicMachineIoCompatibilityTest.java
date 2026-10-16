package com.huanghuang.rsintegration.magic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MagicMachineIoCompatibilityTest {
    private static final Path MODS = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration", "mods");
    private static final Path MAGIC = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration", "magic");

    @Test
    void manaAndSourceMachinesDoNotCallRsiRecursiveEnergy() throws Exception {
        for (String machine : List.of(
                "arsnouveau/ArsImbuementBatchDelegate.java",
                "arsnouveau/ArsApparatusBatchDelegate.java",
                "botania/ManaPoolBatchDelegate.java",
                "botania/RunicAltarBatchDelegate.java",
                "botania/BotanicalBreweryBatchDelegate.java",
                "botania/TerraPlateBatchDelegate.java",
                "botania/MythicBotanyManaInfuserBatchDelegate.java")) {
            String source = Files.readString(MODS.resolve(machine));
            assertFalse(source.contains("MagicCraftingEnergyTask"), machine);
            assertFalse(source.contains("MagicResourceEndpoint"), machine);
            assertFalse(source.contains("MagicResourceTaskReservation"), machine);
        }

        assertTokens(MAGIC.resolve("MagicMachineResourceRecovery.java"),
                List.of("getSource", "removeSource", "getCurrentMana",
                        "receiveMana", "isLoaded"));
    }

    private static void assertTokens(Path file, List<String> tokens) throws Exception {
        String source = Files.readString(file);
        for (String token : tokens) {
            assertTrue(source.contains(token), file + " must retain " + token);
        }
    }
}
