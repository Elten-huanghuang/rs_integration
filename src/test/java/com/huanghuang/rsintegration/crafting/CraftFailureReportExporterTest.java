package com.huanghuang.rsintegration.crafting;

import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CraftFailureReportExporterTest extends BootstrapTest {
    @TempDir Path gameDirectory;

    @Test
    void exportsUtf8UnderTheSelectedGameInstanceAndReturnsAbsolutePath() throws Exception {
        List<Component> report = List.of(Component.literal("\u5408\u6210\u5931\u8d25"), Component.literal("test:recipe"));
        Path file = CraftFailureReportExporter.export(gameDirectory, UUID.randomUUID(), report);
        assertTrue(file.isAbsolute());
        assertEquals(gameDirectory.toAbsolutePath().resolve("rs_integration/reports"), file.getParent());
        assertTrue(file.getFileName().toString().endsWith(".txt"));
        assertEquals("\u5408\u6210\u5931\u8d25\ntest:recipe", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void repeatedExportsDoNotOverwriteEarlierReports() throws Exception {
        UUID craftId = UUID.randomUUID();
        Path first = CraftFailureReportExporter.export(gameDirectory, craftId, List.of(Component.literal("first")));
        Path second = CraftFailureReportExporter.export(gameDirectory, craftId, List.of(Component.literal("second")));
        assertNotEquals(first, second);
        assertEquals("first", Files.readString(first));
        assertEquals("second", Files.readString(second));
        assertTrue(first.getFileName().toString().contains(craftId.toString().substring(0, 8)));
    }

    @Test
    void directoryFailureIsExplicitAndLeavesExistingFilesUntouched() throws Exception {
        Path blocker = gameDirectory.resolve("rs_integration");
        Files.writeString(blocker, "existing file");
        assertThrows(IOException.class, () -> CraftFailureReportExporter.export(gameDirectory,
                UUID.randomUUID(), List.of(Component.literal("report"))));
        assertEquals("existing file", Files.readString(blocker));
    }

    @Test
    void locationLookupDoesNotCreateDirectoriesBeforeExport() {
        Path directory = CraftFailureReportExporter.directory(gameDirectory.resolve("unused/.."));
        assertEquals(gameDirectory.toAbsolutePath().resolve("rs_integration/reports"), directory);
        assertFalse(Files.exists(directory));
    }

    @Test
    void oversizedUnicodeReportExportsWithoutSplittingSurrogatePairs() throws Exception {
        List<Component> report = List.of(Component.literal("\uD83D\uDE00".repeat(70_000)));
        Path file = CraftFailureReportExporter.export(gameDirectory, UUID.randomUUID(), report);
        String contents = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(CraftFailureReport.plainText(report), contents);
        assertTrue(contents.length() <= CraftFailureReport.MAX_REPORT_CHARS);
        assertFalse(contents.contains("\uFFFD"));
        assertTrue(contents.contains("rsi.diagnostic.truncated"));
    }

    @Test
    void screenReportsFullPathWithoutChatOrClipboardWrites() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/huanghuang/rsintegration/"
                + "crafting/CraftFailureScreen.java"));
        assertTrue(source.contains("minecraft.gameDirectory.toPath()"));
        assertTrue(source.contains("\"rsi.diagnostic.export_success\", file.toString()"));
        assertTrue(source.contains("\"rsi.diagnostic.export_directory\", exportDirectory.toString()"));
        assertTrue(source.contains("font.split(line, bodyWidth - 16)"));
        assertTrue(source.contains("\"rsi.diagnostic.export_failure\""));
        assertFalse(source.contains("setClipboard"));
        assertFalse(source.contains("sendSystemMessage"));
        for (String locale : List.of("en_us", "zh_cn")) {
            var translations = JsonParser.parseString(Files.readString(Path.of(
                    "src/main/resources/assets/rs_integration/lang/" + locale + ".json"))).getAsJsonObject();
            for (String suffix : List.of("export", "export_directory", "export_success", "export_failure", "open_directory")) {
                assertTrue(translations.has("rsi.diagnostic." + suffix));
            }
        }
    }
}
