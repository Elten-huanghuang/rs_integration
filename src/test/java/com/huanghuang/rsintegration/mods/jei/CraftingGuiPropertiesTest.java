package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.crafting.MachineManagementScreen;
import com.huanghuang.rsintegration.crafting.planning.PlanningTasksScreen;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CraftingGuiPropertiesTest extends BootstrapTest {
    static Stream<Screen> screens() {
        return Stream.of(mock(PlanningTasksScreen.class), mock(MachineManagementScreen.class));
    }

    @ParameterizedTest
    @MethodSource("screens")
    void openingAndResizingWaitForUsableScreenAndPanelBounds(Screen screen) {
        Rect2i area = new Rect2i(130, 46, 380, 268);
        assertNull(CraftingGuiProperties.of(screen, area));
        screen.width = 640;
        screen.height = 360;
        assertNull(CraftingGuiProperties.of(screen, new Rect2i(0, 0, 0, 0)));
        var properties = CraftingGuiProperties.of(screen, area);
        assertNotNull(properties);
        assertEquals(640, properties.getScreenWidth());
        assertEquals(380, properties.getGuiXSize());
        screen.width = 854;
        screen.height = 480;
        var resized = CraftingGuiProperties.of(screen, new Rect2i(237, 106, 380, 268));
        assertEquals(854, resized.getScreenWidth());
        assertEquals(480, resized.getScreenHeight());
        assertEquals(237, resized.getGuiLeft());
        assertEquals(640, properties.getScreenWidth(), "已发布的帧快照不应被后续窗口尺寸修改");
    }
}
