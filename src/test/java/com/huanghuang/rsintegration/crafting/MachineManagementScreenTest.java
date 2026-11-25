package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.plan.PlanScreenHost;
import com.huanghuang.rsintegration.crafting.planning.PlanningTasksScreen;
import com.huanghuang.rsintegration.machine.client.MachinePreferenceClient;
import com.huanghuang.rsintegration.machine.client.MachineTabHandler;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import com.huanghuang.rsintegration.network.binding.MachineEndpointId;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.GameNarrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MachineManagementScreenTest extends BootstrapTest {
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
    @ValueSource(booleans = {false, true})
    void bothPlanPagesOpenAllBoundMachinesAndReturnToTheSamePlanList(boolean hosted) {
        Minecraft minecraft = client();
        PlanScreenHost host = hosted ? mock(PlanScreenHost.class) : null;
        PlanningTasksScreen plans = hosted
                ? new PlanningTasksScreen(new StorageReference(new StorageBackendId("refinedstorage"), "test"),
                        request -> {}, host)
                : new PlanningTasksScreen(null);
        plans.init(minecraft, 555, 252);
        button(plans, "rsi.control_center.machines").onPress();

        ArgumentCaptor<Screen> opened = ArgumentCaptor.forClass(Screen.class);
        if (hosted) verify(host).rsi$setPlanPage(opened.capture());
        else verify(minecraft).setScreen(opened.capture());
        var machines = assertInstanceOf(MachineManagementScreen.class, opened.getValue());
        assertEquals("rsi.control_center.all_machines", key(machines.getTitle().getContents()));

        try (var handler = mockStatic(MachineTabHandler.class)) {
            machines.init(minecraft, 555, 252);
        }
        button(machines, "gui.back").onPress();
        if (hosted) {
            verify(host).rsi$setPlanPage(plans);
            verify(minecraft, never()).setScreen(nullable(Screen.class));
        } else {
            verify(minecraft).setScreen(plans);
        }
    }

    @Test
    void machinePageOpenedFromTheMonitorUsesItsNetworkPlansAndRunningTasks() {
        Minecraft minecraft = client();
        PlanScreenHost host = mock(PlanScreenHost.class);
        var machines = new MachineManagementScreen(null, MachineManagementScreen.View.ALL_BOUND, host);
        try (var handler = mockStatic(MachineTabHandler.class)) {
            machines.init(minecraft, 555, 252);
            Button plans = button(machines, "rsi.planning.tasks.open");
            assertFalse(plans.active);
            when(host.rsi$canOpenPlans()).thenReturn(true);
            machines.tick();
            assertTrue(plans.active);
            plans.onPress();
        }
        verify(host).rsi$openPlans();
        button(machines, "rsi.planning.tasks.executions").onPress();
        verify(host).rsi$setPlanPage(null);
        verify(minecraft, never()).setScreen(nullable(Screen.class));
    }

    @Test
    void hostedRenameReturnsToTheHostedMachinePage() throws Exception {
        Minecraft minecraft = client();
        PlanScreenHost host = mock(PlanScreenHost.class);
        var machines = new MachineManagementScreen(null, MachineManagementScreen.View.ALL_BOUND, host);
        BindingInfo machine = machine(1).withBookIds(UUID.randomUUID(), MachineEndpointId.create());
        try (var handler = mockStatic(MachineTabHandler.class)) {
            handler.when(MachineTabHandler::getAllBoundMachinesForManagement).thenReturn(List.of(machine));
            machines.init(minecraft, 555, 252);
            int x = number(machines, "gridX") + number(machines, "tileWidth") - 15;
            int y = number(machines, "gridY") + 53;
            assertTrue(machines.mouseClicked(x, y, 0));
        }
        ArgumentCaptor<Screen> opened = ArgumentCaptor.forClass(Screen.class);
        verify(host).rsi$setPlanPage(opened.capture());
        assertInstanceOf(MachineRenameScreen.class, opened.getValue()).onClose();
        verify(host).rsi$setPlanPage(machines);
        verify(minecraft, never()).setScreen(nullable(Screen.class));
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

    private static Minecraft client() {
        Minecraft minecraft = mock(Minecraft.class);
        when(minecraft.getNarrator()).thenReturn(mock(GameNarrator.class));
        return minecraft;
    }

    private static Button button(Screen screen, String translationKey) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> translationKey.equals(key(button.getMessage().getContents())))
                .findFirst().orElseThrow();
    }

    private static String key(Object contents) {
        return contents instanceof TranslatableContents translatable ? translatable.getKey() : "";
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
