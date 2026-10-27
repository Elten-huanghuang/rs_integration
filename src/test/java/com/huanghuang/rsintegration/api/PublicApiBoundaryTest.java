package com.huanghuang.rsintegration.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicApiBoundaryTest {
    private static final List<Path> EXAMPLES = List.of(
            Path.of("src/test/java/com/example/rsiintegration/"
                    + "PublicIntegrationExample.java"),
            Path.of("src/test/java/com/example/rsiintegration/"
                    + "PublicConsumerExample.java"),
            Path.of("src/test/java/com/example/rsiintegration/"
                    + "PublicResonanceExample.java")
    );
    private static final Path PUBLIC_API_ROOT =
            Path.of("src/main/java/com/huanghuang/rsintegration/api");
    private static final Path LEGACY_MAGIC_API =
            PUBLIC_API_ROOT.resolve("magic");
    private static final Path API_SMOKE_ROOT =
            Path.of("src/apismoke/java");

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

    @Test
    void publicApiTypesDoNotImportInternalImplementationPackages() throws Exception {
        try (var files = Files.walk(PUBLIC_API_ROOT)) {
            for (Path sourceFile : files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(LEGACY_MAGIC_API))
                    .toList()) {
                String source = Files.readString(sourceFile);
                if (source.contains("@ApiStatus.Internal")) continue;
                source.lines()
                        .filter(line -> line.startsWith(
                                "import com.huanghuang.rsintegration."))
                        .filter(line -> !line.startsWith(
                                "import com.huanghuang.rsintegration.api."))
                        .forEach(line -> {
                            throw new AssertionError(
                                    "internal import in public API "
                                            + sourceFile + ": " + line);
                        });
            }
        }
    }

    @Test
    void apiSmokeModOnlyImportsThePublicRsiSurface() throws Exception {
        try (var files = Files.walk(API_SMOKE_ROOT)) {
            for (Path sourceFile : files
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                Files.readString(sourceFile)
                        .lines()
                        .filter(line -> line.startsWith(
                                "import com.huanghuang.rsintegration."))
                        .forEach(line -> assertTrue(
                                line.startsWith(
                                        "import com.huanghuang.rsintegration.api."),
                                () -> "internal RSI import in API smoke mod "
                                        + sourceFile + ": " + line
                        ));
            }
        }
    }
}
