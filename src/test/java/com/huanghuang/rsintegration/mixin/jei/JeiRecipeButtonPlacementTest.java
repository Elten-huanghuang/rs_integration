package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.compat.jei.JeiRecipeButtonPlacement;
import net.minecraft.client.renderer.Rect2i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiRecipeButtonPlacementTest {
    @Test
    void resolvesDeclaredAreaAgainstCurrentRecipePosition() {
        Rect2i resolved = JeiRecipeButtonPlacement.resolveTransferArea(
                new Rect2i(240, 100, 120, 60),
                new Rect2i(105, 45, 13, 13),
                new Rect2i(40, 30, 13, 13));

        assertArea(resolved, 345, 145, 13, 13);
    }

    @Test
    void usesCachedButtonOnlyWhenDeclaredAreaIsUnavailable() {
        Rect2i resolved = JeiRecipeButtonPlacement.resolveTransferArea(
                new Rect2i(240, 100, 120, 60),
                new Rect2i(0, 0, 0, 0),
                new Rect2i(345, 145, 13, 13));

        assertArea(resolved, 345, 145, 13, 13);
    }

    @Test
    void anchorsToRecipeCornerWhenJeiHasNoTransferButton() {
        Rect2i resolved = JeiRecipeButtonPlacement.resolveTransferArea(
                new Rect2i(240, 100, 120, 60), null, null);

        assertArea(resolved, 359, 159, 1, 1);
    }

    @Test
    void anchorsBelowTheJeiTransferButtonWhenThereIsRoom() {
        assertArrayEquals(new int[]{80, 42},
                JeiRecipeButtonPlacement.place(
                        new Rect2i(80, 30, 10, 10), 22, 10, 160, 100));
    }

    @Test
    void flipsAboveAtTheBottomAndClampsTheWholeGroupAtTheRightEdge() {
        int[] placement = JeiRecipeButtonPlacement.place(
                new Rect2i(155, 88, 10, 10), 22, 10, 160, 100);

        assertArrayEquals(new int[]{136, 76}, placement);
        assertTrue(placement[0] >= 2 && placement[0] + 22 <= 158);
        assertTrue(placement[1] >= 2 && placement[1] + 10 <= 98);
    }

    @Test
    void repeatedLayoutRefreshesChooseTheSamePosition() {
        Rect2i anchor = new Rect2i(80, 30, 10, 10);
        int[] first = JeiRecipeButtonPlacement.place(
                anchor, 22, 10, 160, 100);
        int[] second = JeiRecipeButtonPlacement.place(
                anchor, 22, 10, 160, 100);

        assertArrayEquals(first, second);
    }

    private static void assertArea(Rect2i area, int x, int y, int width, int height) {
        assertArrayEquals(new int[]{x, y, width, height},
                new int[]{area.getX(), area.getY(), area.getWidth(), area.getHeight()});
    }
}
