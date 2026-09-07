package com.huanghuang.rsintegration.crafting;

import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.ChatFormatting;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.huanghuang.rsintegration.crafting.CraftProgressSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

class CraftFailurePresentationTest extends BootstrapTest {
    @TempDir Path directory;

    @Test
    void reportSeparatesSectionsAndUsesSemanticColors() {
        ItemStack target = new ItemStack(Items.STONE_SWORD);
        target.getOrCreateTag().putBoolean("Unbreakable", true);
        var failed = new NodeProgress(0, NodeState.FAILED, "test:recipe", "test", target,
                0, 1, 0, "minecraft:overworld@1, 2, 3", Reason.OUTPUT_MISSING, "missing output", false);
        var blocked = new NodeProgress(1, NodeState.BLOCKED, "test:next", "test", target,
                0, 1, 0, "", Reason.WAITING_MATERIALS, "waiting", false);
        var snapshot = new CraftProgressSnapshot(UUID.randomUUID(), TERMINAL_SEQUENCE, Result.FAILED,
                Reason.OUTPUT_MISSING, 0, 2, 0, "missing output", List.of(failed, blocked));
        var lines = CraftFailureReport.lines(snapshot, target, CraftFailureContext.unknown("1.4.2"), true);
        assertColor(first(lines, "status"), ChatFormatting.RED);
        assertColor(first(lines, "reason"), ChatFormatting.RED);
        assertColor(first(lines, "check"), ChatFormatting.YELLOW);
        for (String key : List.of("version", "task", "recipe", "machine", "technical", "nbt_entry")) {
            assertColor(first(lines, key), ChatFormatting.GRAY);
        }
        for (String key : List.of("client_observations", "server_snapshot", "section.statistics", "section.steps", "section.nbt")) {
            Component heading = first(lines, key);
            assertColor(heading, ChatFormatting.AQUA);
            assertTrue(heading.getStyle().isBold());
            assertEquals("", lines.get(lines.indexOf(heading) - 1).getString());
        }
        var steps = lines.stream().filter(line -> key(line).equals("rsi.diagnostic.step")).toList();
        assertColor(steps.get(0), ChatFormatting.RED);
        assertColor(steps.get(1), ChatFormatting.YELLOW);
        assertTrue(steps.get(0).getStyle().isBold());
        assertTrue(CraftFailureReport.plainText(lines).contains("\n\n"));
    }

    @Test
    void chatUsesTwoLinesWithoutSpreadingClickOrUnderlineToFailureText() {
        UUID id = UUID.randomUUID();
        Component message = CraftFailureLinks.message(id);
        assertColor(message, ChatFormatting.RED);
        assertFalse(message.getStyle().isUnderlined());
        assertNull(message.getStyle().getClickEvent());
        assertEquals(2, message.getString().split("\n", -1).length);
        Component link = message.getSiblings().stream().filter(part -> part.getStyle().getClickEvent() != null)
                .findFirst().orElseThrow();
        assertColor(link, ChatFormatting.GREEN);
        assertTrue(link.getStyle().isUnderlined());
        assertEquals("rsi_failure_report " + id, CraftFailureLinks.localCommand(link.getStyle()));
    }

    @Test
    void exportKeepsParagraphsAndUserLogHighlightTextWithoutFormattingCodes() throws Exception {
        var translations = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/assets/rs_integration/lang/zh_cn.json"))).getAsJsonObject();
        String guidance = translations.get("rsi.diagnostic.attach_logs").getAsString();
        List<Component> lines = List.of(Component.literal("Failure").withStyle(ChatFormatting.RED), Component.empty(),
                Component.literal(guidance).withStyle(ChatFormatting.YELLOW),
                Component.literal("\u00a7aAdditional text\u00a7r"));
        Path exported = CraftFailureReportExporter.export(directory, UUID.randomUUID(), lines);
        String text = Files.readString(exported);
        assertEquals("Failure\n\n" + ChatFormatting.stripFormatting(guidance) + "\nAdditional text", text);
        assertTrue(text.contains("logs/latest.log"));
        assertTrue(text.contains("logs/debug.log"));
        assertFalse(text.contains("\u00a7"));
        assertFalse(text.contains("\u001b"));
        assertColor(lines.get(0), ChatFormatting.RED);
        assertEquals(guidance, lines.get(2).getString());
    }

    @Test
    void wrappingPreservesStylesAndFitsNarrowTextArea() throws Exception {
        StringSplitter splitter = new StringSplitter((codePoint, style) -> style.isBold() ? 7.0F : 6.0F);
        assertTrue(splitter.splitLines(Component.empty(), 140, Style.EMPTY).isEmpty());
        String screen = Files.readString(Path.of("src/main/java/com/huanghuang/rsintegration/crafting/CraftFailureScreen.java"));
        assertTrue(screen.contains("line.getString().isEmpty()"));
        assertTrue(screen.contains("Stream.of(FormattedCharSequence.EMPTY)"));
        Component content = Component.literal("Details\n" + "test:long_recipe_name_".repeat(40))
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        var rows = splitter.splitLines(content, 140, Style.EMPTY);
        assertTrue(rows.size() > 2);
        assertEquals("Details", rows.get(0).getString());
        for (var row : rows) {
            assertTrue(splitter.stringWidth(row) <= 140);
            row.<Void>visit((style, value) -> {
                if (!value.isEmpty()) {
                    assertEquals(ChatFormatting.AQUA.getColor(), style.getColor().getValue());
                    assertTrue(style.isBold());
                }
                return Optional.empty();
            }, Style.EMPTY);
        }
    }

    @Test
    void bothLanguagesTranslateHeadingsAndBreakUpLongDescriptions() throws Exception {
        for (String locale : List.of("en_us", "zh_cn")) {
            var translations = JsonParser.parseString(Files.readString(Path.of(
                    "src/main/resources/assets/rs_integration/lang/" + locale + ".json"))).getAsJsonObject();
            for (String key : List.of("section.statistics", "section.steps", "section.nbt")) {
                assertFalse(translations.get("rsi.diagnostic." + key).getAsString().isBlank());
            }
            for (String key : List.of("attach_logs", "technical", "check", "nbt_entry")) {
                assertTrue(translations.get("rsi.diagnostic." + key).getAsString().contains("\n"));
            }
        }
    }

    private static Component first(List<Component> lines, String suffix) {
        return lines.stream().filter(line -> key(line).equals("rsi.diagnostic." + suffix)).findFirst().orElseThrow();
    }

    private static String key(Component line) {
        return line.getContents() instanceof TranslatableContents translated ? translated.getKey() : "";
    }

    private static void assertColor(Component component, ChatFormatting color) {
        assertNotNull(component.getStyle().getColor());
        assertEquals(color.getColor(), component.getStyle().getColor().getValue());
    }
}
