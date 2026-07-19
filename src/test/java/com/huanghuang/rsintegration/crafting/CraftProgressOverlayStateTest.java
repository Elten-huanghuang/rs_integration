package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftProgressOverlayStateTest extends BootstrapTest {

    @Test
    void terminalStatesCannotBeCancelled() {
        assertFalse(CraftProgressOverlay.cancellable(CraftProgressSnapshot.Result.SUCCEEDED));
        assertFalse(CraftProgressOverlay.cancellable(CraftProgressSnapshot.Result.FAILED));
        assertFalse(CraftProgressOverlay.cancellable(CraftProgressSnapshot.Result.CANCELLED));
        assertFalse(CraftProgressOverlay.cancellable(CraftProgressSnapshot.Result.STOPPING));
    }

    @Test
    void activeStatesRemainCancellable() {
        assertTrue(CraftProgressOverlay.cancellable(CraftProgressSnapshot.Result.RUNNING));
        assertTrue(CraftProgressOverlay.cancellable(CraftProgressSnapshot.Result.WAITING));
    }

    @Test
    void failedFullNodeCountDoesNotLookSuccessful() {
        CraftProgressSnapshot failed = new CraftProgressSnapshot(UUID.randomUUID(), 3,
                CraftProgressSnapshot.Result.FAILED, CraftProgressSnapshot.Reason.NONE,
                3, 3, 0, null, List.of());
        CraftProgressSnapshot succeeded = new CraftProgressSnapshot(UUID.randomUUID(), 4,
                CraftProgressSnapshot.Result.SUCCEEDED, CraftProgressSnapshot.Reason.NONE,
                3, 3, 0, null, List.of());

        assertEquals(99, CraftProgressOverlay.progressPercent(failed));
        assertEquals(100, CraftProgressOverlay.progressPercent(succeeded));
        assertEquals("rsi.progress.reason.failed_unspecified",
                CraftProgressOverlay.detail(failed).getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents
                        ? contents.getKey() : "");
    }

    @Test
    void currentNodesPreferStableRunningTier() {
        CraftProgressSnapshot.NodeProgress ready = node(0, CraftProgressSnapshot.NodeState.READY, false);
        CraftProgressSnapshot.NodeProgress runningA = node(1, CraftProgressSnapshot.NodeState.RUNNING, false);
        CraftProgressSnapshot.NodeProgress draining = node(2, CraftProgressSnapshot.NodeState.RUNNING, true);
        CraftProgressSnapshot.NodeProgress runningB = node(3, CraftProgressSnapshot.NodeState.RUNNING, false);
        CraftProgressSnapshot snapshot = new CraftProgressSnapshot(UUID.randomUUID(), 1,
                CraftProgressSnapshot.Result.RUNNING, CraftProgressSnapshot.Reason.NONE,
                0, 4, 3, null, List.of(ready, runningA, draining, runningB));

        CraftProgressPresentation.Selection selection =
                CraftProgressPresentation.currentNodes(snapshot, 1);

        assertEquals(List.of(runningA), selection.nodes());
        assertEquals(1, selection.remaining());
    }

    @Test
    void currentNodesFallBackFromFailureToBlockedReason() {
        CraftProgressSnapshot.NodeProgress blocked = new CraftProgressSnapshot.NodeProgress(0,
                CraftProgressSnapshot.NodeState.BLOCKED, "test:blocked", "generic",
                ItemStack.EMPTY, 0, 1, 0, "", CraftProgressSnapshot.Reason.MACHINE_BUSY,
                "", false);
        CraftProgressSnapshot.NodeProgress failed = node(1, CraftProgressSnapshot.NodeState.FAILED, false);
        CraftProgressSnapshot failedSnapshot = new CraftProgressSnapshot(UUID.randomUUID(), 1,
                CraftProgressSnapshot.Result.FAILED, CraftProgressSnapshot.Reason.UNKNOWN,
                0, 2, 0, null, List.of(blocked, failed));
        CraftProgressSnapshot blockedSnapshot = new CraftProgressSnapshot(UUID.randomUUID(), 1,
                CraftProgressSnapshot.Result.WAITING, CraftProgressSnapshot.Reason.NONE,
                0, 1, 0, null, List.of(blocked));

        assertEquals(List.of(failed), CraftProgressPresentation.currentNodes(failedSnapshot, 2).nodes());
        assertEquals(List.of(blocked), CraftProgressPresentation.currentNodes(blockedSnapshot, 2).nodes());
    }

    private static CraftProgressSnapshot.NodeProgress node(
            int id, CraftProgressSnapshot.NodeState state, boolean draining) {
        return new CraftProgressSnapshot.NodeProgress(id, state, "test:step_" + id, "generic",
                new ItemStack(Items.IRON_INGOT), 0, 1, state == CraftProgressSnapshot.NodeState.RUNNING ? 1 : 0,
                "", CraftProgressSnapshot.Reason.NONE, "", draining);
    }

    @Test
    void eachResultUsesItsOwnTranslationKeyAndAccent() {
        for (CraftProgressSnapshot.Result result : CraftProgressSnapshot.Result.values()) {
            assertEquals("rsi.progress.status." + result.name().toLowerCase(java.util.Locale.ROOT),
                    CraftProgressOverlay.titleKey(result));
        }
        assertFalse(CraftProgressOverlay.accent(CraftProgressSnapshot.Result.RUNNING)
                == CraftProgressOverlay.accent(CraftProgressSnapshot.Result.FAILED));
    }

    @Test
    void currentStepDetailsKeepTheirTranslationComponents() {
        CraftProgressSnapshot.NodeProgress node = new CraftProgressSnapshot.NodeProgress(0,
                CraftProgressSnapshot.NodeState.BLOCKED, "test:compact_recipe", "",
                ItemStack.EMPTY, 0, 1, 0, "invalid dimension@1, 2, 3",
                CraftProgressSnapshot.Reason.MACHINE_BUSY, "", false);

        assertEquals("rsi.progress.step.recipe",
                translationKey(CraftProgressPresentation.outputName(node)));
        Component detail = CraftProgressPresentation.machineWithReason(node);
        assertEquals("rsi.progress.step.machine_reason", translationKey(detail));
        Object machineArgument = ((TranslatableContents) detail.getContents()).getArgs()[0];
        assertTrue(machineArgument instanceof Component);
        assertEquals("rsi.progress.step.machine",
                translationKey((Component) machineArgument));
    }

    private static String translationKey(Component component) {
        return component.getContents() instanceof TranslatableContents contents
                ? contents.getKey() : "";
    }
}
