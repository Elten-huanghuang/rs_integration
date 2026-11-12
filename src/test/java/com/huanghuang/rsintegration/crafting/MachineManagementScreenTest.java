package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.machine.client.MachinePreferenceClient;
import com.huanghuang.rsintegration.machine.client.MachineTabHandler;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import com.huanghuang.rsintegration.network.binding.MachineEndpointId;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;

class MachineManagementScreenTest extends BootstrapTest {
    @ParameterizedTest
    @CsvSource({"320,240", "427,240", "480,270", "640,360", "854,480", "960,520", "1920,1080"})
    void machineCardsFitBetweenSearchAndFooterAtDifferentGuiScales(int width, int height) throws Exception {
        MachineManagementScreen screen = layout(width, height, MachineManagementScreen.View.ALL_BOUND);
        int left = number(screen, "panelX"), top = number(screen, "panelY");
        int right = left + number(screen, "panelWidth"), bottom = top + number(screen, "panelHeight");
        int gridX = number(screen, "gridX"), gridY = number(screen, "gridY");
        int gridWidth = number(screen, "gridWidth"), gridHeight = number(screen, "gridHeight");
        int columns = call(screen, "columns"), rows = call(screen, "visibleRows");
        int tileWidth = number(screen, "tileWidth");

        assertTrue(left >= 0 && top >= 0 && right <= width && bottom <= height);
        assertTrue(gridX >= left && gridX + gridWidth < right);
        assertTrue(gridY >= top + 92, "卡片不得覆盖搜索框");
        assertTrue(gridX + (columns - 1) * (tileWidth + 8) + tileWidth <= gridX + gridWidth);
        assertTrue(gridY + (rows - 1) * 76 + 68 <= bottom - number(screen, "footerHeight"),
                "卡片不得覆盖底部按钮");
        assertTrue(gridHeight >= 68 && columns > 0 && rows > 0);
    }

    @ParameterizedTest
    @CsvSource({"320,240", "427,240", "640,360", "854,480"})
    void compactPanelLeavesSpaceForJeiAndContainsAllControls(int width, int height) throws Exception {
        MachineManagementScreen screen = layout(width, height, MachineManagementScreen.View.ALL_BOUND);
        Field font = Screen.class.getDeclaredField("font");
        font.setAccessible(true);
        Font mockFont = mock(Font.class);
        when(mockFont.plainSubstrByWidth(anyString(), anyInt())).thenAnswer(call -> call.getArgument(0));
        when(mockFont.plainSubstrByWidth(anyString(), anyInt(), anyBoolean())).thenAnswer(call -> call.getArgument(0));
        font.set(screen, mockFont);
        try (var handler = mockStatic(MachineTabHandler.class)) {
            screen.init();
        }
        var panel = screen.panelBounds();
        assertTrue(panel.getWidth() <= 480 && panel.getHeight() <= 300);
        assertEquals((width - panel.getWidth()) / 2, panel.getX());
        assertEquals((height - panel.getHeight()) / 2, panel.getY());
        if (width >= 640) assertTrue(panel.getX() >= 80, "3x 视口左侧为 JEI 留出独立列");
        for (var child : screen.children()) if (child instanceof AbstractWidget widget) {
            assertTrue(widget.getX() >= panel.getX());
            assertTrue(widget.getX() + widget.getWidth() <= panel.getX() + panel.getWidth());
            assertTrue(widget.getY() >= panel.getY());
            assertTrue(widget.getY() + widget.getHeight() <= panel.getY() + panel.getHeight());
        }
    }

    @Test
    void unbindUsesTheResizedCardActionArea() throws Exception {
        MachineManagementScreen screen = layout(960, 520, MachineManagementScreen.View.ALL_BOUND);
        BindingInfo machine = machine(1);
        entries(screen).add(machine);
        try (var handler = mockStatic(MachineTabHandler.class)) {
            int x = number(screen, "gridX") + number(screen, "tileWidth") - 8;
            int y = number(screen, "gridY") + 6;
            assertTrue(screen.mouseClicked(x, y, 0));
            handler.verify(() -> MachineTabHandler.onUnbind(machine));
        }
    }

    @Test
    void favoriteUsesTheResizedCardActionArea() throws Exception {
        MachineManagementScreen screen = layout(960, 520, MachineManagementScreen.View.GUI_ONLY);
        BindingInfo machine = machine(1);
        entries(screen).add(machine);
        try (var preferences = mockStatic(MachinePreferenceClient.class);
             var handler = mockStatic(MachineTabHandler.class)) {
            int x = number(screen, "gridX") + number(screen, "tileWidth") - 8;
            int y = number(screen, "gridY") + 6;
            assertTrue(screen.mouseClicked(x, y, 0));
            preferences.verify(() -> MachinePreferenceClient.requestFavoriteToggle(machine));
        }
    }

    @Test
    void rightDragSelectsBothResizedCardsWithoutUnbindingThem() throws Exception {
        MachineManagementScreen screen = layout(960, 520, MachineManagementScreen.View.ALL_BOUND);
        List<BindingInfo> machines = List.of(machine(1), machine(2));
        entries(screen).addAll(machines);
        int x = number(screen, "gridX"), y = number(screen, "gridY");
        int right = x + number(screen, "tileWidth") * 2 + 8 - 2;
        try (var preferences = mockStatic(MachinePreferenceClient.class);
             var handler = mockStatic(MachineTabHandler.class)) {
            assertTrue(screen.mouseClicked(x + 2, y + 2, 1));
            assertTrue(screen.mouseDragged(right, y + 60, 1, right - x, 58));
            assertTrue(screen.mouseReleased(right, y + 60, 1));
            preferences.verify(() -> MachinePreferenceClient.requestAutomationBlocked(machines, true));
        }
    }

    @Test
    void closingTheMachinePageReturnsToItsParent() throws Exception {
        Screen parent = mock(Screen.class);
        Minecraft minecraft = mock(Minecraft.class);
        MachineManagementScreen screen = new MachineManagementScreen(parent, MachineManagementScreen.View.ALL_BOUND);
        Field client = Screen.class.getDeclaredField("minecraft");
        client.setAccessible(true);
        client.set(screen, minecraft);
        screen.onClose();
        verify(minecraft).setScreen(parent);
    }

    @ParameterizedTest
    @EnumSource(MachineManagementScreen.View.class)
    void pencilOpensRenameWithoutOpeningOrUnbindingMachine(MachineManagementScreen.View view)
            throws Exception {
        MachineManagementScreen screen = layout(960, 520, view);
        Minecraft minecraft = mock(Minecraft.class);
        Field client = Screen.class.getDeclaredField("minecraft");
        client.setAccessible(true);
        client.set(screen, minecraft);
        BindingInfo machine = machine(1).withBookIds(UUID.randomUUID(), MachineEndpointId.create());
        entries(screen).add(machine);

        try (var handler = mockStatic(MachineTabHandler.class)) {
            int x = number(screen, "gridX") + number(screen, "tileWidth") - 15;
            int y = number(screen, "gridY") + 53;
            assertTrue(screen.mouseClicked(x, y, 0));
            ArgumentCaptor<Screen> opened = ArgumentCaptor.forClass(Screen.class);
            verify(minecraft).setScreen(opened.capture());
            assertTrue(opened.getValue() instanceof MachineRenameScreen);
            client.set(opened.getValue(), minecraft);
            opened.getValue().onClose();
            verify(minecraft).setScreen(screen);
            handler.verifyNoInteractions();
        }
    }

    private static MachineManagementScreen layout(int width, int height, MachineManagementScreen.View view)
            throws Exception {
        MachineManagementScreen screen = new MachineManagementScreen(null, view);
        screen.width = width;
        screen.height = height;
        call(screen, "updateLayout");
        return screen;
    }

    private static int number(MachineManagementScreen screen, String name) throws Exception {
        Field field = MachineManagementScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(screen);
    }

    private static int call(MachineManagementScreen screen, String name) throws Exception {
        Method method = MachineManagementScreen.class.getDeclaredMethod(name);
        method.setAccessible(true);
        Object result = method.invoke(screen);
        return result instanceof Integer number ? number : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<BindingInfo> entries(MachineManagementScreen screen) throws Exception {
        Field field = MachineManagementScreen.class.getDeclaredField("filtered");
        field.setAccessible(true);
        return (List<BindingInfo>) field.get(screen);
    }

    private static BindingInfo machine(int x) {
        return new BindingInfo("test:terminal", new ResourceLocation("minecraft", "overworld"),
                new BlockPos(x, 64, 0), "test:machine", "Machine", null, null);
    }
}
