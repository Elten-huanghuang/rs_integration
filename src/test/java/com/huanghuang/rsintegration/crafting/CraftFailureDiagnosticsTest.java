package com.huanghuang.rsintegration.crafting;

import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.crafting.batch.CraftStartedPacket;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.huanghuang.rsintegration.crafting.CraftProgressSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

class CraftFailureDiagnosticsTest extends BootstrapTest {
    @AfterEach
    void clearTracker() { CraftProgressTracker.clear(); }

    @Test
    void outputShortageAndTimeoutAreNotMisreportedAsMissingInputs() {
        assertEquals(Reason.OUTPUT_MISSING, AsyncCraftChain.progressReasonForDetail(
                "Output shortage: minecraft:diamond missing=1"));
        assertEquals(Reason.TIMEOUT, AsyncCraftChain.progressReasonForDetail("Timeout waiting for output"));
        assertEquals(Reason.TIMEOUT, AsyncCraftChain.progressReasonForDetail("Exceeded global time limit"));
        assertEquals(Reason.MATERIAL_EXTRACTION_FAILED,
                AsyncCraftChain.progressReasonForDetail("missing material minecraft:iron_ingot"));
        assertEquals(Reason.NETWORK_UNAVAILABLE,
                AsyncCraftChain.progressReasonForDetail("network unavailable"));
        assertEquals(Reason.START_REJECTED, AsyncCraftChain.progressReasonForDetail(
                "Enchanting Apparatus preparation rejected: requires 3 Arcane Pedestals, found 0"));
        assertEquals(Reason.UNKNOWN, AsyncCraftChain.progressReasonForDetail("unrecognized failure"));
    }

    @Test
    void reportIncludesStepMachineAndOriginalEvidenceButNotItemNbt() {
        ItemStack target = new ItemStack(Items.DIAMOND);
        target.getOrCreateTag().putString("private_inventory_data", "must_not_export");
        var node = new NodeProgress(3, NodeState.FAILED, "test:upgrade", "ironsspellbooks",
                target, 1, 2, 0, "minecraft:overworld@1, 2, 3", Reason.START_REJECTED,
                "machine rejected ink", false);
        List<Component> lines = CraftFailureReport.lines(failed(List.of(node)), target, "1.4.2");
        assertTrue(lines.stream().anyMatch(line -> key(line).equals("rsi.diagnostic.attach_logs")));
        String serialized = lines.stream().map(Component.Serializer::toJson)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(serialized.contains("test:upgrade"));
        assertTrue(serialized.contains("minecraft:overworld@1, 2, 3"));
        assertTrue(serialized.contains("machine rejected ink"));
        assertFalse(serialized.contains("private_inventory_data"));
        assertFalse(serialized.contains("must_not_export"));
    }

    @Test
    void reportPrioritizesFailedNodesAndLimitsLongTaskDetails() {
        List<NodeProgress> nodes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            nodes.add(new NodeProgress(i, NodeState.BLOCKED, "test:blocked_" + i, "generic",
                    ItemStack.EMPTY, 0, 1, 0, "", Reason.WAITING_MATERIALS, "waiting", false));
        }
        nodes.add(new NodeProgress(100, NodeState.FAILED, "test:root_failure", "generic",
                ItemStack.EMPTY, 0, 1, 0, "", Reason.OUTPUT_MISSING, "missing output", false));
        List<Component> lines = CraftFailureReport.lines(failed(nodes), ItemStack.EMPTY, "1.4.2");
        var steps = lines.stream().filter(line -> key(line).equals("rsi.diagnostic.step")).toList();
        assertEquals(CraftFailureReport.MAX_REPORT_NODES, steps.size());
        assertEquals(101, ((TranslatableContents) steps.get(0).getContents()).getArgs()[0]);
        assertTrue(lines.stream().anyMatch(line -> key(line).equals("rsi.diagnostic.omitted")));
    }

    @Test
    void unknownStepIsExplicitAndDoesNotInventMachinePosition() {
        List<Component> lines = CraftFailureReport.lines(failed(List.of()), ItemStack.EMPTY, "1.4.2");
        assertTrue(lines.stream().anyMatch(line -> key(line).equals("rsi.diagnostic.no_step")));
        assertFalse(lines.stream().anyMatch(line -> key(line).equals("rsi.diagnostic.machine")));
    }

    @Test
    void failedTaskSummaryDoesNotClaimItIsStillWaitingForMachineResponse() {
        assertEquals("rsi.progress.reason.failed_unspecified",
                key(CraftProgressOverlay.detail(failed(List.of()))));
        var node = new NodeProgress(0, NodeState.FAILED, "test:failure", "generic", ItemStack.EMPTY,
                0, 1, 0, "", Reason.OUTPUT_MISSING, "missing output", false);
        assertEquals(Reason.OUTPUT_MISSING.translationKey(),
                key(CraftProgressOverlay.detail(failed(List.of(node)))));
    }

    @Test
    void oversizedFailureDetailsStillRoundTripThroughProgressPacket() {
        String oversized = "x".repeat(1011) + "\uD83D\uDE00".repeat(600);
        var node = new NodeProgress(0, NodeState.FAILED, "test:failure", "generic", ItemStack.EMPTY,
                0, 1, 0, "", Reason.UNKNOWN, oversized, false);
        var snapshot = new CraftProgressSnapshot(UUID.randomUUID(), TERMINAL_SEQUENCE, Result.FAILED,
                Reason.UNKNOWN, 0, 1, 0, oversized, List.of(node));
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            new com.huanghuang.rsintegration.crafting.batch.CraftProgressPacket(snapshot).encode(buffer);
            var decoded = com.huanghuang.rsintegration.crafting.batch.CraftProgressPacket.decode(buffer).snapshot();
            assertTrue(decoded.technicalDetail().length() <= MAX_TECHNICAL_DETAIL_LENGTH);
            assertTrue(decoded.technicalDetail().endsWith(" [truncated]"));
            assertEquals(snapshot.technicalDetail(), decoded.technicalDetail());
            assertEquals(node.technicalDetail(), decoded.nodes().get(0).technicalDetail());
            assertFalse(decoded.technicalDetail().contains("\uFFFD"));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rawTextAndExportHaveBoundsAndDoNotInjectNewLines() {
        assertEquals("one two three", CraftFailureReport.safeText("one\ntwo\tthree"));
        assertEquals(1024, CraftFailureReport.safeText("x".repeat(2000)).length());
        assertFalse(CraftFailureReport.safeText("a\u202Eb").contains("\u202E"));
        String text = CraftFailureReport.plainText(List.of(Component.literal("x".repeat(100_000))));
        assertEquals(CraftFailureReport.MAX_REPORT_CHARS, text.length());
        assertTrue(text.contains("rsi.diagnostic.truncated"));
    }

    @Test
    void historyKeepsOnlyEightFailuresAndCopiesTargets() {
        CraftFailureHistory history = new CraftFailureHistory();
        ItemStack target = new ItemStack(Items.DIAMOND, 4);
        CraftProgressSnapshot oldest = failed(List.of());
        history.record(oldest, target);
        target.setCount(1);
        assertEquals(4, history.target(oldest.craftId()).getCount());
        ItemStack exported = history.target(oldest.craftId());
        exported.setCount(2);
        assertEquals(4, history.target(oldest.craftId()).getCount());
        for (int i = 0; i < 8; i++) history.record(failed(List.of()), target);
        assertEquals(8, history.snapshots().size());
        assertTrue(history.target(oldest.craftId()).isEmpty());
        history.record(new CraftProgressSnapshot(UUID.randomUUID(), TERMINAL_SEQUENCE,
                Result.SUCCEEDED, Reason.NONE, 1, 1, 0, null), target);
        assertEquals(8, history.snapshots().size());
    }

    @Test
    void terminalHistorySurvivesAuthoritativeActiveTaskSyncAndCanBeDismissed() {
        CraftProgressSnapshot failure = failed(List.of());
        CraftProgressTracker.onStarted(new CraftStartedPacket(failure.craftId(), 1, false,
                new ItemStack(Items.DIAMOND)));
        CraftProgressTracker.onProgress(failure);
        CraftProgressTracker.retainOnly(List.of());
        assertTrue(CraftProgressTracker.snapshots().isEmpty());
        assertEquals(List.of(failure), List.copyOf(CraftProgressTracker.taskSnapshots()));
        assertEquals(Items.DIAMOND, CraftProgressTracker.target(failure.craftId()).getItem());
        CraftProgressTracker.dismissFailure(failure.craftId());
        assertTrue(CraftProgressTracker.taskSnapshots().isEmpty());
    }

    @Test
    void historyDoesNotKeepHudVisibleAfterLiveEntryIsRemovedAndClearsOnDisconnect() {
        CraftProgressSnapshot failure = failed(List.of());
        CraftProgressTracker.onProgress(failure);
        CraftProgressTracker.remove(failure.craftId());
        assertFalse(CraftProgressTracker.hasActive());
        assertEquals(1, CraftProgressTracker.taskSnapshots().size());
        CraftProgressTracker.clear();
        assertTrue(CraftProgressTracker.taskSnapshots().isEmpty());
    }

    @Test
    void dismissCannotRemoveRunningCrafts() {
        UUID id = UUID.randomUUID();
        CraftProgressTracker.onStarted(new CraftStartedPacket(id, 1, false));
        CraftProgressTracker.dismissFailure(id);
        assertNotNull(CraftProgressTracker.get(id));
    }

    @Test
    void everyAdviceCategoryHasBothTranslationsAndPreservesUncertainty() throws Exception {
        for (String locale : List.of("en_us", "zh_cn")) {
            var json = JsonParser.parseString(Files.readString(Path.of(
                    "src/main/resources/assets/rs_integration/lang/" + locale + ".json"))).getAsJsonObject();
            for (Reason reason : Reason.values()) assertTrue(json.has(CraftFailureReport.hintKey(reason)));
            assertTrue(json.has("rsi.diagnostic.machine_unknown"));
            assertTrue(json.has("rsi.diagnostic.no_step"));
            String logGuidance = json.get("rsi.diagnostic.attach_logs").getAsString();
            assertTrue(logGuidance.contains("logs/latest.log"));
            assertTrue(logGuidance.contains("logs/debug.log"));
        }
        assertEquals("rsi.diagnostic.hint.timeout", CraftFailureReport.hintKey(Reason.TIMEOUT));
        assertEquals("rsi.diagnostic.hint.unknown", CraftFailureReport.hintKey(Reason.UNKNOWN));
    }

    private static CraftProgressSnapshot failed(List<NodeProgress> nodes) {
        return new CraftProgressSnapshot(UUID.randomUUID(), TERMINAL_SEQUENCE, Result.FAILED,
                Reason.UNKNOWN, 0, Math.max(1, nodes.size()), 0, "task aborted", nodes);
    }

    private static String key(Component line) {
        return line.getContents() instanceof TranslatableContents translated ? translated.getKey() : "";
    }
}
