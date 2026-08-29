package com.huanghuang.rsintegration.mods.embers;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EreAlchemyInferProgressContractTest {
    private static final Path SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "mods", "embers", "EreAlchemyInferDelegate.java");

    @Test
    void missingRetryMaterialsPauseWithSpecificMessages() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("rsi.embers.infer.paused_missing_tablet"));
        assertTrue(source.contains("rsi.embers.infer.paused_missing_aspect"));
        assertTrue(source.contains("rsi.embers.infer.paused_missing_input"));
        assertTrue(source.contains("CraftPacketUtils.describeIngredient(missing)"));
    }

    @Test
    void progressUsesPersistentSavedDataWithoutTtl() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("EreAlchemyProgressSavedData.get"));
        assertTrue(source.contains("putProgress"));
        assertTrue(source.contains("getProgress"));
    }

    @Test
    void failedResultsAreConsumedFromBothOutputLocations() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("this.extractFailedResult()"));
        assertTrue(source.contains("Reflect.invoke(binInv, \"extractItem\", 0, 64, false)"));
    }
}
