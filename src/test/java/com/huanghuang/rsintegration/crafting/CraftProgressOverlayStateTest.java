package com.huanghuang.rsintegration.crafting;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftProgressOverlayStateTest {

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
    void eachResultUsesItsOwnTranslationKeyAndAccent() {
        for (CraftProgressSnapshot.Result result : CraftProgressSnapshot.Result.values()) {
            assertEquals("rsi.progress.status." + result.name().toLowerCase(java.util.Locale.ROOT),
                    CraftProgressOverlay.titleKey(result));
        }
        assertFalse(CraftProgressOverlay.accent(CraftProgressSnapshot.Result.RUNNING)
                == CraftProgressOverlay.accent(CraftProgressSnapshot.Result.FAILED));
    }
}
