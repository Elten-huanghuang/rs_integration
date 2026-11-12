package com.huanghuang.rsintegration.crafting.planning;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.plan.PlanBackgroundClient;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressTracker;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class PlanningHudVisibilityTest extends BootstrapTest {
    @BeforeEach
    void loadConfig() {
        CommentedConfig values = CommentedConfig.inMemory();
        RSIntegrationConfig.CLIENT_SPEC.correct(values);
        RSIntegrationConfig.CLIENT_SPEC.setConfig(values);
    }

    @AfterEach
    void clear() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(null);
        PlanningProgressTracker.clear();
        PlanBackgroundClient.clear();
    }

    @Test
    void buttonHidesAndRestoresOnlyThePlanningHud() throws Exception {
        PlanningProgressTracker.start(10, new ResourceLocation("minecraft", "iron_block"));
        PlanningProgressTracker.start(11, new ResourceLocation("minecraft", "gold_block"));
        boolean executionVisible = CraftProgressTracker.isVisible();
        PlanningTasksScreen screen = screen(640, 360);
        Button button = screen.children().stream().filter(Button.class::isInstance)
                .map(Button.class::cast).filter(widget -> widget.getMessage().equals(
                        Component.translatable("rsi.planning.tasks.hide"))).findFirst().orElseThrow();

        button.onPress();
        assertFalse(PlanningProgressOverlay.isVisible());
        assertFalse(RSIntegrationConfig.PLANNING_HUD_VISIBLE.get());
        assertEquals(Component.translatable("rsi.planning.tasks.show"), button.getMessage());
        assertEquals(2, PlanningTasksScreen.taskCount());
        assertTrue(PlanningProgressTracker.acceptsResponse(10));
        assertTrue(PlanningProgressTracker.acceptsResponse(11));
        assertEquals(executionVisible, CraftProgressTracker.isVisible());

        PlanningProgressTracker.start(12, new ResourceLocation("minecraft", "diamond_block"));
        assertFalse(PlanningProgressOverlay.isVisible());
        button.onPress();
        assertTrue(PlanningProgressOverlay.isVisible());
        assertEquals(3, PlanningTasksScreen.taskCount());
        assertEquals(Component.translatable("rsi.planning.tasks.hide"), button.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"320,240", "427,240", "640,360", "854,480"})
    void compactPaginatedPanelContainsControlsWithoutOverlapping(int width, int height) throws Exception {
        for (long request = 1; request <= 8; request++) {
            PlanningProgressTracker.start(request, new ResourceLocation("minecraft", "iron_block"));
        }
        PlanningTasksScreen screen = screen(width, height);
        var panel = screen.panelBounds();
        assertTrue(panel.getWidth() <= 380 && panel.getHeight() <= 300);
        assertEquals((width - panel.getWidth()) / 2, panel.getX());
        assertEquals((height - panel.getHeight()) / 2, panel.getY());
        if (width >= 640) assertTrue(panel.getX() >= 130, "3x 视口左侧为 JEI 留出独立列");
        var widgets = screen.children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).toList();
        for (int i = 0; i < widgets.size(); i++) {
            AbstractWidget widget = widgets.get(i);
            assertTrue(widget.getX() >= panel.getX());
            assertTrue(widget.getX() + widget.getWidth() <= panel.getX() + panel.getWidth());
            assertTrue(widget.getY() >= panel.getY());
            assertTrue(widget.getY() + widget.getHeight() <= panel.getY() + panel.getHeight());
            for (int j = 0; j < i; j++) {
                AbstractWidget other = widgets.get(j);
                assertTrue(widget.getX() + widget.getWidth() <= other.getX()
                        || other.getX() + other.getWidth() <= widget.getX()
                        || widget.getY() + widget.getHeight() <= other.getY()
                        || other.getY() + other.getHeight() <= widget.getY(), "列表、导航和底部按钮不得重叠");
            }
        }
        var footer = screen.children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(widget -> widget.getHeight() == 22)
                .sorted((left, right) -> Integer.compare(left.getX(), right.getX())).toList();
        int footerY = footer.stream().mapToInt(AbstractWidget::getY).max().orElseThrow();
        var buttons = footer.stream().filter(widget -> widget.getY() == footerY).toList();
        assertEquals(4, buttons.size());
        for (int i = 0; i < buttons.size(); i++) {
            AbstractWidget button = buttons.get(i);
            assertTrue(button.getX() >= 0 && button.getX() + button.getWidth() <= width);
            assertTrue(button.getY() >= 0 && button.getY() + button.getHeight() <= height);
            if (i > 0) assertTrue(buttons.get(i - 1).getX() + buttons.get(i - 1).getWidth() <= button.getX());
        }
    }

    @ParameterizedTest
    @CsvSource({"320,240", "640,360", "854,480"})
    void compactRowsKeepLastPageAndCancelTargetAccessible(int width, int height) throws Exception {
        for (long request = 1; request <= 8; request++) {
            PlanningProgressTracker.start(request, new ResourceLocation("minecraft", "iron_block"));
        }
        PlanningTasksScreen screen = screen(width, height);
        Button next;
        int turns = 0;
        while ((next = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().equals(Component.literal("›")))
                .findFirst().orElseThrow()).active) {
            assertTrue(++turns < 8, "分页必须能到达最后一页");
            next.onPress();
        }
        assertTrue(turns > 0);
        var rows = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getHeight() > 22).toList();
        Button last = rows.get(rows.size() - 1);
        try (var overlay = mockStatic(PlanningProgressOverlay.class)) {
            assertTrue(last.mouseClicked(last.getX() + last.getWidth() - 16, last.getY() + 18, 0));
            overlay.verify(() -> PlanningProgressOverlay.cancel(1L));
        }
    }

    @Test
    void visibilityLookupIsSafeBeforeClientConfigLoads() {
        RSIntegrationConfig.CLIENT_SPEC.setConfig(null);
        assertEquals(RSIntegrationConfig.PLANNING_HUD_VISIBLE.getDefault(), PlanningProgressOverlay.isVisible());
        PlanningProgressOverlay.toggleVisible();
        assertEquals(RSIntegrationConfig.PLANNING_HUD_VISIBLE.getDefault(), PlanningProgressOverlay.isVisible());
    }

    private static PlanningTasksScreen screen(int width, int height) throws Exception {
        PlanningTasksScreen screen = new PlanningTasksScreen((Screen) null);
        screen.width = width;
        screen.height = height;
        Field font = Screen.class.getDeclaredField("font");
        font.setAccessible(true);
        font.set(screen, mock(Font.class));
        screen.init();
        return screen;
    }
}
