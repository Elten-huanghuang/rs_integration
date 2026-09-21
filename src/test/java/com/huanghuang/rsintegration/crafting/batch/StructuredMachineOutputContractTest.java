package com.huanghuang.rsintegration.crafting.batch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredMachineOutputContractTest {

    @Test
    void slotOutputDelegatesPublishTheirStructuredPortIds() throws IOException {
        assertPublishesStructuredOutput(
                "mods/aether/AetherFurnaceBatchDelegate.java",
                "aether:furnace:output", "collectResult(player)");
        assertPublishesStructuredOutput(
                "mods/forbidden/ClibanoBatchDelegate.java",
                "forbidden_arcanus:clibano:output", "collectAllResults(player)");
        assertPublishesStructuredOutput(
                "mods/immortalersdelight/EnchantalCoolerBatchDelegate.java",
                "immortalers_delight:cooler:output", "collectResult(player)");
        assertPublishesStructuredOutput(
                "mods/youkaishomecoming/moka/MokaPotBatchDelegate.java",
                "youkaishomecoming:moka:output", "collectResult(player)");
    }

    private static void assertPublishesStructuredOutput(String relativePath, String portId,
                                                        String collectionCall)
            throws IOException {
        Path source = Path.of("src", "main", "java", "com", "huanghuang", "rsintegration")
                .resolve(relativePath);
        String code = Files.readString(source, StandardCharsets.UTF_8);
        int method = code.indexOf("collectStructuredResults(");
        assertTrue(method >= 0, () -> source + " does not publish structured slot output");
        int nextOverride = code.indexOf("@Override", method + 1);
        String body = nextOverride < 0 ? code.substring(method) : code.substring(method, nextOverride);
        assertTrue(body.contains(collectionCall),
                () -> source + " does not physically collect its slot output");
        assertTrue(body.contains("\"" + portId + "\""),
                () -> source + " publishes the wrong structured output port");
        assertTrue(body.contains("OutputContract.Source.SLOT"),
                () -> source + " publishes the wrong structured output source");
    }
}
