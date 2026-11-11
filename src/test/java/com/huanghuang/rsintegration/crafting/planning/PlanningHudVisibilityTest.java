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

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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

    @Test
    void paginatedFooterKeepsTheVisibilityAndNavigationButtonsApartAtMinimumSize() throws Exception {
        for (long request = 1; request <= 8; request++) {
            PlanningProgressTracker.start(request, new ResourceLocation("minecraft", "iron_block"));
        }
        PlanningTasksScreen screen = screen(320, 240);
        var footer = screen.children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(widget -> widget.getHeight() == 22)
                .sorted((left, right) -> Integer.compare(left.getX(), right.getX())).toList();
        int footerY = footer.stream().mapToInt(AbstractWidget::getY).max().orElseThrow();
        var buttons = footer.stream().filter(widget -> widget.getY() == footerY).toList();
        assertEquals(4, buttons.size());
        for (int i = 0; i < buttons.size(); i++) {
            AbstractWidget button = buttons.get(i);
            assertTrue(button.getX() >= 0 && button.getX() + button.getWidth() <= 320);
            assertTrue(button.getY() >= 0 && button.getY() + button.getHeight() <= 240);
            if (i > 0) assertTrue(buttons.get(i - 1).getX() + buttons.get(i - 1).getWidth() <= button.getX());
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
