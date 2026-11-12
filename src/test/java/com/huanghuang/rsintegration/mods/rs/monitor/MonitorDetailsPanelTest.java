package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.client.gui.CraftingUiStyle.ActionButton;
import com.huanghuang.rsintegration.client.gui.ListScrollBar;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.NodeState;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot.Reason;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.screen.CraftingMonitorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

class MonitorDetailsPanelTest extends BootstrapTest {
    @ParameterizedTest
    @CsvSource({"112,201", "132,201", "152,201", "152,231", "240,201"})
    void diagnosisFocusTracksTheCauseAndRestoresTheListWhenItResolves(int width, int height) throws Exception {
        var client = mock(Minecraft.class);
        var font = mock(Font.class);
        when(font.plainSubstrByWidth(anyString(), anyInt())).thenAnswer(call -> call.getArgument(0));
        when(font.plainSubstrByWidth(anyString(), anyInt(), anyBoolean())).thenAnswer(call -> call.getArgument(0));
        var fontField = Minecraft.class.getDeclaredField("font");
        fontField.setAccessible(true);
        fontField.set(client, font);
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            var widgets = new ArrayList<AbstractWidget>();
            var selected = new AtomicInteger(-1);
            var screen = mock(CraftingMonitorScreen.class);
            when(screen.getElements()).thenReturn(List.of());
            var panel = new MonitorDetailsPanel(screen, 10, 20, width, height,
                    widgets::add, selected::set, "", true);
            UUID id = UUID.randomUUID();
            var running = node(1, NodeState.RUNNING, Reason.NONE, new ItemStack(Items.DIAMOND));
            var blocked = node(2, NodeState.READY, Reason.NO_BOUND_MACHINE, new ItemStack(Items.IRON_INGOT));
            var queue = node(3, NodeState.READY, Reason.MACHINE_BUSY, new ItemStack(Items.GOLD_INGOT));
            panel.setVisible(true);
            panel.update(task(id, List.of(running, blocked, queue)), id);
            var search = (EditBox) widgets.stream().filter(EditBox.class::isInstance).findFirst().orElseThrow();
            var diagnosis = (ActionButton) widgets.stream().filter(widget -> widget instanceof ActionButton && widget.getWidth() == width)
                    .findFirst().orElseThrow();
            var bar = (ListScrollBar) widgets.stream().filter(ListScrollBar.class::isInstance).findFirst().orElseThrow();
            assertTrue(diagnosis.visible && diagnosis.active);
            assertTrue(diagnosis.getY() + diagnosis.getHeight() < search.getY());
            assertTrue(search.getY() + search.getHeight() < bar.getY());
            assertTrue(bar.getY() + bar.getHeight() <= 20 + height - 6);
            assertFalse(panel.mouseClicked(15, search.getY() + 1, 0));
            diagnosis.onPress();
            panel.mouseClicked(15, bar.getY() + 1, 0);
            assertEquals(2, selected.get(), "关注列表按节点身份定位对应材料");
            selected.set(-1);
            panel.mouseClicked(15, bar.getY() + 45, 0);
            assertEquals(-1, selected.get(), "关注模式不应混入运行或排队步骤");
            var resolved = node(2, NodeState.SUCCEEDED, Reason.NONE, new ItemStack(Items.IRON_INGOT));
            panel.update(task(id, List.of(running, resolved, queue)), id);
            panel.mouseClicked(15, bar.getY() + 1, 0);
            assertEquals(1, selected.get(), "关注原因解决后自动恢复完整活动列表");
            panel.update(mock(CraftingMonitorScreen.Task.class), UUID.randomUUID());
            assertFalse(diagnosis.visible);
            assertEquals(20 + 27, search.getY(), "原生任务仍使用紧凑材料详情布局");
            assertEquals(20 + 46, bar.getY());
        }
    }

    private static RsiMonitorTask task(UUID id, List<NodeProgress> nodes) {
        var snapshot = new CraftProgressSnapshot(id, 1, CraftProgressSnapshot.Result.RUNNING,
                Reason.NONE, 0, nodes.size(), 1, null, nodes);
        var task = mock(RsiMonitorTask.class);
        when(task.snapshot()).thenReturn(snapshot);
        when(task.diagnostics()).thenReturn(MonitorDiagnostics.from(snapshot));
        when(task.materialIndex(any(ItemStack.class))).thenAnswer(call -> {
            var item = ((ItemStack) call.getArgument(0)).getItem();
            return item == Items.DIAMOND ? 1 : item == Items.IRON_INGOT ? 2 : 3;
        });
        return task;
    }

    private static NodeProgress node(int id, NodeState state, Reason reason, ItemStack item) {
        return new NodeProgress(id, state, "test:step_" + id, "vanilla_furnace", item,
                0, 10, state == NodeState.RUNNING ? 1 : 0, "overworld@1,2,3", reason, "", false);
    }

    @ParameterizedTest
    @ValueSource(ints = {112, 132, 152, 240})
    void compactSearchAndFilterFitSideBySideAndKeepTheirStateWhenHidden(int width) throws Exception {
        var client = mock(Minecraft.class);
        var font = mock(Font.class);
        when(font.plainSubstrByWidth(anyString(), anyInt())).thenAnswer(call -> call.getArgument(0));
        var fontField = Minecraft.class.getDeclaredField("font");
        fontField.setAccessible(true);
        fontField.set(client, font);
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            var widgets = new ArrayList<AbstractWidget>();
            var panel = new MonitorDetailsPanel(mock(CraftingMonitorScreen.class), 10, 20, width, 231,
                    widgets::add, index -> {}, "furnace", true);
            var search = (EditBox) widgets.stream().filter(EditBox.class::isInstance).findFirst().orElseThrow();
            var filter = (ActionButton) widgets.stream().filter(ActionButton.class::isInstance).findFirst().orElseThrow();
            assertTrue(search.getWidth() < width * 2 / 3, "搜索框不再铺满右栏");
            assertTrue(search.getX() + search.getWidth() < filter.getX(), "搜索和筛选不能重叠");
            assertTrue(filter.getX() + filter.getWidth() <= 10 + width);
            assertTrue(search.getY() >= filter.getY());
            assertTrue(search.getY() + search.getHeight() <= filter.getY() + filter.getHeight());
            assertFalse(panel.mouseClicked(search.getX() + 1, search.getY() + 1, 0), "步骤列表不能截获搜索输入");

            panel.setVisible(true);
            search.setValue("iron");
            filter.onPress();
            assertEquals("iron", panel.query());
            assertFalse(panel.onlyActive());
            panel.setVisible(false);
            assertTrue(widgets.stream().noneMatch(widget -> widget.visible));
            panel.setVisible(true);
            assertTrue(search.visible && filter.visible);
            assertEquals("iron", panel.query());
            assertFalse(panel.onlyActive());
        }
    }
}
