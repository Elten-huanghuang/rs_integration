package com.huanghuang.rsintegration.crafting.progress;

import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeState;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.Reason;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.Result;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftProgressOverlayTest extends BootstrapTest {
    @ParameterizedTest
    @CsvSource({"320,180,9", "854,480,9", "1920,1080,12", "320,180,16"})
    void compactHudStaysInsideViewportWithoutReducingFontSize(int width, int height, int lineHeight) {
        var layout = CraftProgressOverlay.layout(width, height, 1000, lineHeight, false);

        assertTrue(layout.visible());
        assertTrue(layout.width() <= 176);
        assertTrue(layout.height() <= 76);
        assertTrue(layout.x() >= 0 && layout.x() + layout.width() <= width);
        assertTrue(layout.y() >= 0 && layout.y() + layout.height() <= height);
    }

    @Test
    void abnormalReasonOnlyAddsOneLineAndShortTargetsCanUseLessWidth() {
        var normal = CraftProgressOverlay.layout(320, 180, 70, 9, false);
        var abnormal = CraftProgressOverlay.layout(320, 180, 70, 9, true);
        var longName = CraftProgressOverlay.layout(320, 180, 1000, 9, true);

        assertTrue(abnormal.visible());
        assertTrue(abnormal.height() <= 80);
        assertTrue(abnormal.height() > normal.height());
        assertTrue(normal.width() < longName.width());
        assertEquals(normal.width(), abnormal.width());
    }

    @ParameterizedTest
    @CsvSource({"320,180", "427,240", "854,480"})
    void shortTargetMovesRightWithoutKeepingAWideEmptyPanel(int width, int height) {
        var layout = CraftProgressOverlay.layout(width, height, 64, 9, false);

        assertTrue(layout.visible());
        assertTrue(layout.width() <= 100);
        assertEquals(4, width - layout.x() - layout.width());
        assertTrue(layout.x() > width - 128);
    }

    @ParameterizedTest
    @CsvSource({"100,180", "320,50", "0,0"})
    void unusablySmallViewportDoesNotDrawAnOverflowingPanel(int width, int height) {
        assertFalse(CraftProgressOverlay.layout(width, height, 1000, 9, true).visible());
    }

    @ParameterizedTest
    @CsvSource({"1,×1", "64,×64", "12500,×12K"})
    void targetQuantityRemainsVisibleIncludingSingleItemsAndLargeBatches(int count, String expected) {
        ItemStack target = new ItemStack(Items.DIAMOND, count);

        assertEquals(expected, CraftProgressOverlay.hudQuantity(target));
        assertEquals(count, target.getCount());
    }

    @Test
    void fluidQuantityKeepsItsUnitAndUnknownTargetsDoNotInventAQuantity() {
        ItemStack fluid = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(),
                new FluidStack(Fluids.WATER, 250));

        assertEquals("250 mB", CraftProgressOverlay.hudQuantity(fluid));
        assertEquals("", CraftProgressOverlay.hudQuantity(ItemStack.EMPTY));
    }

    @Test
    void compactHintUsesTheActualKeyInsteadOfHardCodingP() {
        Component reboundKey = Component.literal("O");
        Component hint = CraftProgressOverlay.hudHint(reboundKey);

        assertEquals("rsi.progress.hud.hint", key(hint));
        assertArrayEquals(new Object[]{reboundKey}, ((TranslatableContents) hint.getContents()).getArgs());
    }

    @Test
    void keyHintFitsBesideTheProgressBarWithoutAddingASeparateTextRow() {
        var normal = CraftProgressOverlay.layout(427, 240, 100, 9, false);
        var abnormal = CraftProgressOverlay.layout(427, 240, 100, 9, true);

        assertEquals(58, normal.height());
        assertEquals(71, abnormal.height());
        assertEquals(4, 427 - normal.x() - normal.width());
    }

    @Test
    void healthyTaskDoesNotReserveSpaceForRepeatedPreparingOrUnknownMessages() {
        assertTrue(CraftProgressOverlay.hudDetail(snapshot(Result.RUNNING, Reason.NONE, List.of()))
                .getString().isEmpty());
        assertTrue(CraftProgressOverlay.hudDetail(snapshot(Result.RUNNING, Reason.UNKNOWN, List.of()))
                .getString().isEmpty());
        var oldReason = node(NodeState.SUCCEEDED, Reason.MACHINE_BUSY, 2, 2);
        assertTrue(CraftProgressOverlay.hudDetail(snapshot(Result.RUNNING, Reason.NONE, List.of(oldReason)))
                .getString().isEmpty());
    }

    @Test
    void compactHudRetainsActionableTaskAndActiveNodeReasons() {
        assertEquals(Reason.NETWORK_UNAVAILABLE.translationKey(), key(CraftProgressOverlay.hudDetail(
                snapshot(Result.WAITING, Reason.NETWORK_UNAVAILABLE, List.of()))));
        var blocked = node(NodeState.BLOCKED, Reason.WAITING_MATERIALS, 0, 3);
        assertEquals(Reason.WAITING_MATERIALS.translationKey(), key(CraftProgressOverlay.hudDetail(
                snapshot(Result.RUNNING, Reason.NONE, List.of(blocked)))));
    }

    @Test
    void failureWithoutKnownCauseNeverClaimsItIsWaitingForMachineResponse() {
        assertEquals("rsi.progress.reason.failed_unspecified", key(CraftProgressOverlay.hudDetail(
                snapshot(Result.FAILED, Reason.UNKNOWN, List.of()))));
        var failed = node(NodeState.FAILED, Reason.OUTPUT_MISSING, 1, 3);
        assertEquals(Reason.OUTPUT_MISSING.translationKey(), key(CraftProgressOverlay.hudDetail(
                snapshot(Result.FAILED, Reason.UNKNOWN, List.of(failed)))));
    }

    @Test
    void stoppingAndTerminalStatesRemainDistinctWithoutRepeatingTheirStatusAsDetail() {
        for (Result result : List.of(Result.STOPPING, Result.SUCCEEDED, Result.CANCELLED)) {
            assertTrue(CraftProgressOverlay.hudDetail(snapshot(result, Reason.PLAYER_CANCELLED, List.of()))
                    .getString().isEmpty());
            assertEquals("rsi.progress.status." + result.name().toLowerCase(Locale.ROOT),
                    key(CraftProgressOverlay.title(result)));
        }
    }

    @Test
    void multiStepProgressUsesNodeCountsInsteadOfOutputOrOperationCounts() {
        var node = node(NodeState.RUNNING, Reason.NONE, 10, 40);
        var snapshot = new CraftProgressSnapshot(UUID.randomUUID(), 1, Result.RUNNING, Reason.NONE,
                3, 6, 2, null, List.of(node));
        Component progress = CraftProgressOverlay.hudProgress(snapshot);

        assertEquals("rsi.progress.hud.steps", key(progress));
        assertArrayEquals(new Object[]{3, 6}, ((TranslatableContents) progress.getContents()).getArgs());
        assertEquals(50, CraftProgressOverlay.progressPercent(snapshot));
    }

    @Test
    void singleStepBatchShowsUsefulOperationCountsInsteadOfZeroOfOne() {
        Component progress = CraftProgressOverlay.hudProgress(snapshot(Result.RUNNING, Reason.NONE,
                List.of(node(NodeState.RUNNING, Reason.NONE, 10, 40))));

        assertEquals("rsi.progress.summary_operations", key(progress));
        assertArrayEquals(new Object[]{10, 40}, ((TranslatableContents) progress.getContents()).getArgs());
        assertTrue(CraftProgressOverlay.hudProgress(snapshot(Result.RUNNING, Reason.NONE,
                List.of(node(NodeState.RUNNING, Reason.NONE, 0, 1)))).getString().isEmpty());
    }

    @Test
    void compactHudLabelsExistInBothLanguages() throws Exception {
        for (String language : List.of("zh_cn", "en_us")) {
            var json = JsonParser.parseString(Files.readString(Path.of(
                    "src/main/resources/assets/rs_integration/lang/" + language + ".json"))).getAsJsonObject();
            assertTrue(json.has("rsi.progress.hud.steps"), language);
            assertFalse(json.get("rsi.progress.hud.steps").getAsString().isBlank(), language);
            assertTrue(json.has("rsi.progress.hud.hint"), language);
            assertTrue(json.get("rsi.progress.hud.hint").getAsString().contains("%s"), language);
        }
    }

    @ParameterizedTest
    @CsvSource({"0,0/64,0", "4,4/64,6", "32,32/64,50", "64,64/64,99"})
    void hudShowsOutputQuantityAndDrivesItsBarFromTheSameProgress(long completed, String expected, int percent) {
        var snapshot = outputSnapshot(Result.RUNNING, completed, 64L);

        assertEquals(expected, CraftProgressOverlay.hudQuantity(snapshot, new ItemStack(Items.DIAMOND)));
        assertEquals(percent, CraftProgressOverlay.hudProgressPercent(snapshot));
        assertTrue(CraftProgressOverlay.hudProgress(snapshot).getString().isEmpty());
    }

    @Test
    void failedOrCancelledCraftsDoNotPretendThatAllOutputsWereProduced() {
        for (Result result : List.of(Result.FAILED, Result.CANCELLED)) {
            var snapshot = outputSnapshot(result, 12L, 64L);
            assertEquals("12/64", CraftProgressOverlay.hudQuantity(snapshot, new ItemStack(Items.DIAMOND)));
            assertEquals(18, CraftProgressOverlay.hudProgressPercent(snapshot));
        }
        assertEquals(100, CraftProgressOverlay.hudProgressPercent(outputSnapshot(Result.SUCCEEDED, 64L, 64L)));
    }

    @Test
    void outputRatiosKeepFluidUnitsAndSupportQuantitiesLargerThanAnItemStack() {
        ItemStack fluid = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), new FluidStack(Fluids.WATER, 1));
        assertEquals("250/1K mB", CraftProgressOverlay.hudQuantity(outputSnapshot(Result.RUNNING, 250L, 1000L), fluid));
        assertEquals("1G/4G", CraftProgressOverlay.hudQuantity(
                outputSnapshot(Result.RUNNING, 1_000_000_000L, 4_000_000_000L), new ItemStack(Items.DIAMOND)));
        assertEquals(50, CraftProgressOverlay.hudProgressPercent(
                outputSnapshot(Result.RUNNING, Long.MAX_VALUE / 2L, Long.MAX_VALUE)));
    }

    private static CraftProgressSnapshot outputSnapshot(Result result, long completed, long total) {
        return new CraftProgressSnapshot(UUID.randomUUID(), 1, result, Reason.NONE,
                0, 1, 1, null, List.of(node(NodeState.RUNNING, Reason.NONE, 0, 16)),
                new CraftOutputProgress(completed, total));
    }

    private static CraftProgressSnapshot snapshot(Result result, Reason reason, List<NodeProgress> nodes) {
        return new CraftProgressSnapshot(UUID.randomUUID(), 1, result, reason,
                0, 1, 1, null, nodes);
    }

    private static NodeProgress node(NodeState state, Reason reason, int completed, int total) {
        return new NodeProgress(0, state, "test:craft", "generic", ItemStack.EMPTY,
                completed, total, 0, "", reason, "", false);
    }

    private static String key(Component component) {
        return ((TranslatableContents) component.getContents()).getKey();
    }
}
