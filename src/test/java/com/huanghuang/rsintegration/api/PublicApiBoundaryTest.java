package com.huanghuang.rsintegration.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicApiBoundaryTest {
    private static final List<Path> EXAMPLES = List.of(
            Path.of("src/examplemod/java/com/example/rsiintegration/"
                    + "examplemod/RSIIntegrationExampleMod.java"),
            Path.of("src/test/java/com/example/rsiintegration/"
                    + "PublicIntegrationExample.java")
    );

    @Test
    void thirdPartyExampleOnlyImportsThePublicApiSurface() throws Exception {
        for (Path example : EXAMPLES) {
            Files.readString(example)
                    .lines()
                    .filter(line -> line.startsWith(
                            "import com.huanghuang.rsintegration."))
                    .forEach(line -> assertTrue(
                            line.startsWith(
                                    "import com.huanghuang.rsintegration.api."),
                            () -> "internal API import in "
                                    + example + ": " + line
                    ));
        }
    }

    @Test
    void exampleDoesNotImportInternalAdapterPackages() throws Exception {
        for (Path example : EXAMPLES) {
            String source = Files.readString(example);
            assertFalse(source.contains(
                    "com.huanghuang.rsintegration.crafting."));
            assertFalse(source.contains(
                    "com.huanghuang.rsintegration.mods."));
            assertFalse(source.contains(
                    "com.huanghuang.rsintegration.network."));
            assertFalse(source.contains(
                    "com.huanghuang.rsintegration.recipe."));
        }
    }
}
