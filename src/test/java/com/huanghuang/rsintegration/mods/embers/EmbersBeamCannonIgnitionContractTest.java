package com.huanghuang.rsintegration.mods.embers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EmbersBeamCannonIgnitionContractTest {
    private static final Path EMBERS_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration", "mods", "embers");

    @Test
    void alchemyDelegatesCannotSparkTabletDirectly() throws IOException {
        String batch = Files.readString(EMBERS_SOURCE.resolve("EreAlchemyBatchDelegate.java"));
        String infer = Files.readString(EMBERS_SOURCE.resolve("EreAlchemyInferDelegate.java"));

        assertFalse(batch.contains("sparkProgress"));
        assertFalse(infer.contains("sparkProgress"));
        assertTrue(batch.contains("EmbersBeamCannonIgnition.ignite"));
        assertTrue(infer.contains("EmbersBeamCannonIgnition.ignite"));
    }

    @Test
    void ignitionUsesBeamCannonFireAndChecksItsStoredEmber() throws IOException {
        String source = Files.readString(EMBERS_SOURCE.resolve("EmbersBeamCannonIgnition.java"));

        assertTrue(source.contains("getEmber"));
        assertTrue(source.contains("REQUIRED_EMBER = 1000.0"));
        assertTrue(source.contains("\"fire\""));
    }

    @Test
    void lowEmberWaitsTenSecondsBeforeFailingTheCraft() throws IOException {
        String ignition = Files.readString(EMBERS_SOURCE.resolve("EmbersBeamCannonIgnition.java"));
        String batch = Files.readString(EMBERS_SOURCE.resolve("EreAlchemyBatchDelegate.java"));
        String infer = Files.readString(EMBERS_SOURCE.resolve("EreAlchemyInferDelegate.java"));

        assertTrue(ignition.contains("ENERGY_WAIT_TICKS = 10 * 20"));
        assertTrue(batch.contains("waitingForEmber"));
        assertTrue(batch.contains("level.getGameTime() < this.emberWaitDeadline"));
        assertTrue(infer.contains("WAITING_FOR_EMBER"));
        assertTrue(infer.contains("level.getGameTime() < this.emberWaitDeadline"));
    }
}
