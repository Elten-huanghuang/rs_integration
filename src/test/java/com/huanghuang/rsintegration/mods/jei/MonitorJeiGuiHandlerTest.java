package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.crafting.plan.PlanScreenHost;
import com.huanghuang.rsintegration.client.gui.CraftingPanelBounds;
import com.huanghuang.rsintegration.mods.rs.monitor.MonitorTaskList;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.render.IElementDrawers;
import com.refinedmods.refinedstorage.screen.CraftingMonitorScreen;
import com.refinedmods.refinedstorage.screen.widget.sidebutton.SideButton;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.gui.handlers.IGuiProperties;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitorJeiGuiHandlerTest extends BootstrapTest {
    @Test
    void uninitializedMonitorDoesNotPublishZeroDimensionsAndRecoversAfterInit() {
        CraftingMonitorScreen screen = mock(CraftingMonitorScreen.class);
        mainPanel(screen, 0, 0, 340, 201);
        MonitorJeiGuiHandler handler = new MonitorJeiGuiHandler();
        assertNull(handler.apply(screen));

        screen.width = 640;
        screen.height = 360;
        mainPanel(screen, 184, 79, 340, 201);
        assertBounds(handler.apply(screen), 184, 79, 340, 201);
        assertEquals(640, handler.apply(screen).getScreenWidth());
    }

    @Test
    void optionalRsRegistrationIncludesTheMonitor() {
        IGuiHandlerRegistration registration = mock(IGuiHandlerRegistration.class);
        RSJeiOptionalHooks.registerGridGuiHandler(registration);
        verify(registration).addGuiScreenHandler(eq(CraftingMonitorScreen.class), isA(MonitorJeiGuiHandler.class));
        verify(registration, never()).addGuiContainerHandler(eq(CraftingMonitorScreen.class), any());
    }

    @Test
    void guiBoundsStartAtTaskRailSoBookmarksHaveAFullHeightColumnAfterResize() {
        CraftingMonitorScreen screen = mock(CraftingMonitorScreen.class);
        screen.width = 640;
        screen.height = 360;
        mainPanel(screen, 142, 34, 400, 201);
        MonitorTaskList tasks = new MonitorTaskList(52, 34, 86, 201,
                mock(IElementDrawers.class), id -> {}, widget -> {});
        doReturn(List.of(tasks)).when(screen).children();
        SideButton visible = button(545, 40, 18, 18);
        SideButton hidden = button(590, 300, 18, 18);
        hidden.visible = false;
        when(screen.getSideButtons()).thenReturn(List.of(visible, hidden));
        MonitorJeiGuiHandler handler = new MonitorJeiGuiHandler();

        IGuiProperties properties = handler.apply(screen);
        assertBounds(properties, 52, 34, 511, 201);
        assertEquals(640, properties.getScreenWidth());
        assertEquals(360, properties.getScreenHeight());
        assertEquals(screen.getClass(), properties.getScreenClass());

        tasks.setX(100);
        tasks.setY(80);
        tasks.setWidth(96);
        screen.width = 854;
        screen.height = 480;
        mainPanel(screen, 200, 80, 476, 231);
        when(visible.getX()).thenReturn(680);
        when(visible.getY()).thenReturn(86);
        properties = handler.apply(screen);
        assertBounds(properties, 100, 80, 598, 231);
        assertEquals(854, properties.getScreenWidth());
        assertEquals(480, properties.getScreenHeight());
        tasks.visible = false;
        assertBounds(handler.apply(screen), 200, 80, 498, 231);
    }

    @Test
    void hostedPlanUsesItsOwnPanelAndRestoresMonitorBoundsOnReturn() {
        CraftingMonitorScreen screen = mock(CraftingMonitorScreen.class,
                withSettings().extraInterfaces(PlanScreenHost.class));
        PlanScreenHost host = (PlanScreenHost) screen;
        screen.width = 640;
        screen.height = 360;
        mainPanel(screen, 142, 34, 400, 201);
        Screen page = mock(Screen.class, withSettings().extraInterfaces(CraftingPanelBounds.class));
        when(((CraftingPanelBounds) page).panelBounds()).thenReturn(new Rect2i(130, 46, 380, 268));
        when(host.rsi$planPage()).thenReturn(page);
        MonitorJeiGuiHandler handler = new MonitorJeiGuiHandler();
        assertBounds(handler.apply(screen), 130, 46, 380, 268);

        when(host.rsi$planPage()).thenReturn(mock(Screen.class));
        assertBounds(handler.apply(screen), 0, 0, 640, 360);

        when(host.rsi$planPage()).thenReturn(null);
        assertBounds(handler.apply(screen), 142, 34, 400, 201);
    }

    private static void mainPanel(CraftingMonitorScreen screen, int x, int y, int width, int height) {
        when(screen.getGuiLeft()).thenReturn(x);
        when(screen.getGuiTop()).thenReturn(y);
        when(screen.getXSize()).thenReturn(width);
        when(screen.getYSize()).thenReturn(height);
    }

    private static SideButton button(int x, int y, int width, int height) {
        SideButton button = mock(SideButton.class);
        button.visible = true;
        when(button.getX()).thenReturn(x);
        when(button.getY()).thenReturn(y);
        when(button.getWidth()).thenReturn(width);
        when(button.getHeight()).thenReturn(height);
        return button;
    }

    private static void assertBounds(IGuiProperties area, int x, int y, int width, int height) {
        assertEquals(x, area.getGuiLeft());
        assertEquals(y, area.getGuiTop());
        assertEquals(width, area.getGuiXSize());
        assertEquals(height, area.getGuiYSize());
    }
}
