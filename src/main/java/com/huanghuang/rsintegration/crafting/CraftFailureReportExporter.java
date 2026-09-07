package com.huanghuang.rsintegration.crafting;

import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** Explicit local export to a unique UTF-8 text file in the current game instance. */
public final class CraftFailureReportExporter {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private CraftFailureReportExporter() {}

    public static Path directory(Path gameDirectory) {
        return gameDirectory.toAbsolutePath().normalize().resolve("rs_integration").resolve("reports");
    }

    public static Path export(Path gameDirectory, UUID craftId, List<Component> report) throws IOException {
        Path directory = directory(gameDirectory);
        Files.createDirectories(directory);
        String prefix = "craft-failure-" + LocalDateTime.now().format(TIMESTAMP)
                + "-" + craftId.toString().substring(0, 8) + "-";
        Path file = Files.createTempFile(directory, prefix, ".txt");
        try {
            Files.writeString(file, CraftFailureReport.plainText(report), StandardCharsets.UTF_8);
            return file;
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }
}
