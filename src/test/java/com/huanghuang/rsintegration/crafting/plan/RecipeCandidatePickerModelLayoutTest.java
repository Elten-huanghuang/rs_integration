package com.huanghuang.rsintegration.crafting.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeCandidatePickerModelLayoutTest {

    @Test
    void panelStaysInsideTheTreeViewport() {
        RecipeCandidatePickerModel.Bounds bounds =
                RecipeCandidatePickerModel.layout(1920, 1080, 80, 820);

        assertTrue(bounds.y() >= 80);
        assertTrue(bounds.y() + bounds.height() <= 816);
        assertTrue(bounds.x() + bounds.width() <= 1920);
        assertEquals(bounds.x() - 8, bounds.contentRight());
    }

    @Test
    void shortTreeViewportStillLeavesUsablePanel() {
        RecipeCandidatePickerModel.Bounds bounds =
                RecipeCandidatePickerModel.layout(1280, 720, 60, 260);

        assertTrue(bounds.height() > RecipeCandidatePickerModel.HEADER_HEIGHT
                + RecipeCandidatePickerModel.FOOTER_HEIGHT);
        assertTrue(bounds.y() + bounds.height() <= 256);
    }

    @Test
    void panelWidensForLargeRecipeLayoutsButStaysOnScreen() {
        RecipeCandidatePickerModel.Bounds wide =
                RecipeCandidatePickerModel.layout(1920, 1080, 80, 820, 280);
        RecipeCandidatePickerModel.Bounds narrow =
                RecipeCandidatePickerModel.layout(640, 480, 40, 360, 600);

        assertEquals(280, wide.width());
        assertTrue(narrow.width() <= 640 * 45 / 100);
        assertTrue(narrow.x() + narrow.width() <= 640);
    }
}
