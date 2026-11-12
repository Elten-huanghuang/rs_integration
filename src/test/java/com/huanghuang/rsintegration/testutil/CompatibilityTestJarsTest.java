package com.huanghuang.rsintegration.testutil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityTestJarsTest {
    @TempDir
    Path directory;

    @Test
    void localDecoratedNamesTakePrecedenceOverTheCache() throws IOException {
        Path local = Files.createDirectory(directory.resolve("libs"));
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Path expected = Files.createFile(local.resolve("[诡厄巫法] Goety-2.5.60.1-new_texture.jar"));
        Files.createFile(cache.resolve("goety-2.5.60.1.jar"));

        assertEquals(expected, CompatibilityTestJars.requireMatching(
                ".*goety-2\\.5\\.60\\.1(?:[-+_].*)?\\.jar", List.of(local, cache)));
    }

    @Test
    void aMissingLocalDirectoryFallsBackToTheFixtureCache() throws IOException {
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Path expected = Files.createFile(cache.resolve("EnchantingInfuser-v8.0.3.jar"));

        assertEquals(expected, CompatibilityTestJars.requireMatching("EnchantingInfuser-v8\\.0\\.3\\.jar",
                List.of(directory.resolve("absent"), cache)));
    }

    @Test
    void aDifferentPatchVersionCannotSatisfyTheRequestedFixture() throws IOException {
        Files.createFile(directory.resolve("goety-2.5.60.10.jar"));

        FileNotFoundException failure = assertThrows(FileNotFoundException.class,
                () -> CompatibilityTestJars.requireMatching(
                        ".*goety-2\\.5\\.60\\.1(?:[-+_].*)?\\.jar", List.of(directory)));
        assertTrue(failure.getMessage().contains("prepareCompatibilityTestJars"));
    }

    @Test
    void ambiguousLocalArtifactsAreReportedInsteadOfSelectingAnArbitraryJar() throws IOException {
        Files.createFile(directory.resolve("goety-2.5.60.1.jar"));
        Files.createFile(directory.resolve("[诡厄巫法] goety-2.5.60.1.jar"));

        IOException failure = assertThrows(IOException.class,
                () -> CompatibilityTestJars.requireMatching(".*goety-2\\.5\\.60\\.1\\.jar", List.of(directory)));
        assertTrue(failure.getMessage().contains("多个文件"));
    }
}
