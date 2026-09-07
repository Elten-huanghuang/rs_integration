package com.huanghuang.rsintegration.crafting;

import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.crafting.batch.CraftStartedPacket;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.huanghuang.rsintegration.crafting.CraftProgressSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

class CraftFailureMetadataTest extends BootstrapTest {
    @AfterEach void clear() { CraftProgressTracker.clear(); }

    @Test
    void receiptTimeModeTargetAndEnvironmentAreFrozenAcrossOpeningAndResync() {
        var snapshot = failure(List.of());
        ItemStack target = new ItemStack(Items.STONE_SWORD);
        target.getOrCreateTag().putBoolean("Unbreakable", true);
        Instant before = Instant.now();
        CraftProgressTracker.onStarted(new CraftStartedPacket(snapshot.craftId(), 1, true, target));
        assertTrue(CraftProgressTracker.onProgress(snapshot));
        var entry = CraftProgressTracker.failure(snapshot.craftId());
        assertFalse(entry.context().receivedAt().toInstant().isBefore(before));
        assertFalse(entry.context().receivedAt().toInstant().isAfter(Instant.now()));
        assertEquals(ZoneId.systemDefault(), entry.context().receivedAt().getZone());
        assertEquals("graph", entry.context().mode());
        assertTrue(entry.context().clientVersions().keySet().containsAll(List.of("minecraft", "forge", "rs_integration")));
        String first = text(CraftFailureReport.lines(entry.snapshot(), entry.target(), entry.context(), false));
        target.getOrCreateTag().putInt("Damage", 19);
        entry.target().getOrCreateTag().putInt("Damage", 30);
        CraftProgressTracker.retainOnly(List.of());
        CraftProgressTracker.onProgress(snapshot);
        assertSame(entry, CraftProgressTracker.failure(snapshot.craftId()));
        assertEquals(0, entry.target().getDamageValue());
        assertEquals(first, text(CraftFailureReport.lines(entry.snapshot(), entry.target(), entry.context(), false)));
        assertThrows(UnsupportedOperationException.class, () -> entry.context().clientVersions().put("forge", "changed"));
    }

    @Test
    void contextCopiesVersionMapAndHistoryDoesNotOverwriteFirstReceipt() {
        Map<String, String> versions = new LinkedHashMap<>(Map.of("rs_integration", "1.4.2"));
        var context = new CraftFailureContext(ZonedDateTime.parse("2026-09-07T13:00:00+08:00[Asia/Shanghai]"), "flat", versions);
        versions.put("rs_integration", "changed");
        var snapshot = failure(List.of());
        CraftFailureHistory history = new CraftFailureHistory();
        assertTrue(history.record(snapshot, new ItemStack(Items.DIAMOND, 3), context));
        assertFalse(history.record(snapshot, ItemStack.EMPTY, CraftFailureContext.unknown("other")));
        assertSame(context, history.get(snapshot.craftId()).context());
        assertEquals("1.4.2", context.clientVersions().get("rs_integration"));
        assertEquals(3, history.target(snapshot.craftId()).getCount());
    }

    @Test
    void reportsStatsIdsSnapshotSequenceAndUnknownServerEvidence() {
        var failed = node(0, NodeState.FAILED, Reason.OUTPUT_MISSING, new ItemStack(Items.DIAMOND, 3), 2, true);
        var blocked = node(1, NodeState.BLOCKED, Reason.WAITING_MATERIALS, ItemStack.EMPTY, 0, false);
        var snapshot = failure(List.of(failed, blocked));
        var lines = CraftFailureReport.lines(snapshot, new ItemStack(Items.STONE_SWORD), "1.4.2");
        String text = text(lines);
        assertTrue(text.contains("minecraft:stone_sword"));
        assertTrue(text.contains("minecraft:diamond"));
        assertTrue(text.contains("FAILED=1"));
        assertTrue(text.contains("BLOCKED=1"));
        assertTrue(text.contains("OUTPUT_MISSING=1"));
        assertTrue(text.contains("WAITING_MATERIALS=1"));
        assertEquals(Integer.toString(TERMINAL_SEQUENCE), args(lines, "sequence")[0]);
        assertArrayEquals(new Object[]{2, 2, 2L, 1}, args(lines, "activity"));
        assertNotNull(args(lines, "times_unknown"));
        assertNotNull(args(lines, "inputs_unknown"));
        assertNotNull(args(lines, "environment_scope"));
        assertEquals(Component.translatable("rsi.diagnostic.unknown").getString(), args(lines, "received")[0]);
        var absent = CraftFailureReport.lines(failure(List.of()), ItemStack.EMPTY, "1.4.2");
        Component nbtPresence = (Component) args(absent, "target_item")[2];
        assertEquals("rsi.diagnostic.unknown", ((TranslatableContents) nbtPresence.getContents()).getKey());
        CraftProgressTracker.onProgress(snapshot);
        assertEquals("unknown", CraftProgressTracker.failure(snapshot.craftId()).context().mode());
    }

    @Test
    void detailedNbtExcludesHealthyStepsAndNeverMutatesInputs() {
        ItemStack target = tagged("SpellLevel", "NaN");
        ItemStack failedOutput = tagged("Enchantments", "failed_only");
        ItemStack healthyOutput = tagged("private_inventory", "must_not_include");
        CompoundTag original = target.getTag().copy();
        var snapshot = failure(List.of(node(0, NodeState.FAILED, Reason.INPUT_CONFLICT, failedOutput, 0, false),
                node(1, NodeState.SUCCEEDED, Reason.NONE, healthyOutput, 0, false)));
        String basic = text(CraftFailureReport.lines(snapshot, target, "1.4.2"));
        assertFalse(basic.contains("SpellLevel"));
        String detailed = text(CraftFailureReport.lines(snapshot, target, CraftFailureContext.unknown("1.4.2"), true));
        assertTrue(detailed.contains("SpellLevel"));
        assertTrue(detailed.contains("NaN"));
        assertTrue(detailed.contains("failed_only"));
        assertFalse(detailed.contains("must_not_include"));
        assertNotNull(args(CraftFailureReport.lines(snapshot, target, CraftFailureContext.unknown("1.4.2"), true), "nbt_scope"));
        assertEquals(original, target.getTag());
    }

    @Test
    void nbtEntryAndOverallPayloadLimitsAreExplicit() {
        for (int size : List.of(1, 8000)) {
            List<NodeProgress> nodes = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                nodes.add(node(index, NodeState.FAILED, Reason.UNKNOWN, tagged("Spell", "x".repeat(size)), 0, false));
            }
            var lines = CraftFailureReport.lines(failure(nodes), tagged("Damage", "0"), CraftFailureContext.unknown("1.4.2"), true);
            List<TranslatableContents> entries = lines.stream().map(Component::getContents)
                    .filter(content -> content instanceof TranslatableContents translated && translated.getKey().equals("rsi.diagnostic.nbt_entry"))
                    .map(TranslatableContents.class::cast).toList();
            assertTrue(entries.size() <= CraftFailureNbt.MAX_ENTRIES);
            int total = 0;
            for (var entry : entries) {
                String payload = (String) entry.getArgs()[2];
                assertTrue(payload.length() <= CraftFailureNbt.MAX_ENTRY_CHARS);
                total += payload.length();
            }
            assertTrue(total <= CraftFailureNbt.MAX_TOTAL_CHARS);
            assertNotNull(args(lines, "nbt_omitted"));
            assertTrue(CraftFailureReport.plainText(lines).length() <= CraftFailureReport.MAX_REPORT_CHARS);
        }
    }

    @Test
    void nbtTraversalBoundsArraysDepthUnicodeAndPrioritizesMatchingFields() {
        CompoundTag tag = new CompoundTag();
        tag.putString("aaa_large", "x".repeat(100_000));
        tag.putInt("Damage", 0);
        tag.putBoolean("Unbreakable", true);
        tag.putFloat("SpellPower", Float.NaN);
        tag.putInt("Food", 7);
        String excerpt = CraftFailureNbt.excerpt(tag, 2048);
        assertTrue(excerpt.contains("Damage"));
        assertTrue(excerpt.contains("Unbreakable"));
        assertTrue(excerpt.contains("NaN"));
        assertTrue(excerpt.contains("Food"));
        assertTrue(excerpt.endsWith(CraftFailureNbt.TRUNCATED));
        assertTrue(excerpt.length() <= 2048);
        CompoundTag nested = new CompoundTag();
        CompoundTag current = nested;
        for (int index = 0; index < 40; index++) {
            CompoundTag next = new CompoundTag();
            current.put("nested", next);
            current = next;
        }
        assertTrue(CraftFailureNbt.excerpt(nested, 2048).contains(CraftFailureNbt.TRUNCATED));
        assertTrue(CraftFailureNbt.excerpt(new net.minecraft.nbt.IntArrayTag(new int[10_000]), 2048).contains(CraftFailureNbt.TRUNCATED));
        String unicode = CraftFailureNbt.excerpt(net.minecraft.nbt.StringTag.valueOf("\uD83D\uDE00".repeat(10_000)), 2048);
        assertTrue(unicode.length() <= 2048);
        assertFalse(unicode.contains("\uFFFD"));
        String body = unicode.substring(0, unicode.length() - CraftFailureNbt.TRUNCATED.length());
        assertFalse(Character.isHighSurrogate(body.charAt(body.length() - 1)));
    }

    @Test
    void newDiagnosticKeysHaveEnglishAndChineseTranslations() throws Exception {
        for (String language : List.of("en_us", "zh_cn")) {
            var json = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/rs_integration/lang/" + language + ".json"))).getAsJsonObject();
            for (String name : List.of("chat_failure", "chat_link", "chat_hover", "link_expired", "unknown",
                    "client_observations", "received", "environment", "environment_scope", "server_snapshot", "sequence", "mode",
                    "times_unknown", "target_item", "output_item", "nbt_present", "nbt_absent", "states", "reasons", "activity",
                    "node_activity", "inputs_unknown", "detailed", "nbt_scope", "nbt_entry", "nbt_omitted", "nbt_none")) {
                assertTrue(json.has("rsi.diagnostic." + name), language + ": " + name);
                assertFalse(json.get("rsi.diagnostic." + name).getAsString().isBlank());
            }
        }
    }

    private static ItemStack tagged(String key, String value) {
        ItemStack stack = new ItemStack(Items.STONE_SWORD);
        stack.getOrCreateTag().putString(key, value);
        return stack;
    }

    private static NodeProgress node(int id, NodeState state, Reason reason, ItemStack output, int running, boolean draining) {
        return new NodeProgress(id, state, "test:recipe", "test", output, 0, 3, running,
                "minecraft:overworld@1, 2, 3", reason, "raw evidence", draining);
    }

    private static CraftProgressSnapshot failure(List<NodeProgress> nodes) {
        return new CraftProgressSnapshot(UUID.randomUUID(), TERMINAL_SEQUENCE, Result.FAILED,
                Reason.UNKNOWN, 0, Math.max(1, nodes.size()), 0, null, nodes);
    }

    private static String text(List<Component> lines) {
        return lines.stream().map(Component.Serializer::toJson).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static Object[] args(List<Component> lines, String name) {
        return lines.stream().map(Component::getContents).filter(content -> content instanceof TranslatableContents translated
                        && translated.getKey().equals("rsi.diagnostic." + name))
                .map(content -> ((TranslatableContents) content).getArgs()).findFirst().orElseThrow();
    }
}
