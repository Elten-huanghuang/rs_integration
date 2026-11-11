package com.huanghuang.rsintegration.crafting.planning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class PlanningHudLayoutTest {
    @ParameterizedTest
    @CsvSource({"320,180", "427,240", "854,480", "1920,1080"})
    void defaultKeepsTheWholeHintAwayFromTheSurvivalHud(int width, int height) {
        var layout = PlanningHudLayout.place(width, height, 196, 132, 54, 10, 1, .5);
        assertTrue(layout.visible());
        assertEquals(width - 10, layout.x() + layout.width());
        assertEquals(height / 2, layout.y() + layout.height() / 2);
        assertTrue(layout.y() + layout.height() < height - 60,
                "完整提示（含打开计划说明）不能进入血条/护甲区域");
    }

    @Test
    void anchorsClampTheWholePanelAfterGuiScaleChanges() {
        for (int width : new int[]{320, 427, 854}) {
            for (int height : new int[]{180, 240, 480}) {
                for (double position : new double[]{-1, 0, .5, 1, 2, Double.NaN}) {
                    var layout = PlanningHudLayout.place(width, height, 196, 132, 54, 10,
                            position, position);
                    assertTrue(layout.visible());
                    assertTrue(layout.x() >= 10 && layout.x() + layout.width() <= width - 10);
                    assertTrue(layout.y() >= 10 && layout.y() + layout.height() <= height - 10);
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"0,0", "100,180", "320,50"})
    void smallViewportsHideRatherThanDrawingOutsideTheWindow(int width, int height) {
        assertFalse(PlanningHudLayout.place(width, height, 196, 132, 54, 10, 0, 0).visible());
    }
}
