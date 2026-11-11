package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.client.gui.ListScrollState;
import com.huanghuang.rsintegration.client.gui.ListScrollBar;
import com.huanghuang.rsintegration.client.gui.ScrollBarStyle;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.blockentity.craftingmonitor.ICraftingMonitor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitorScrollingTest extends BootstrapTest {
    @Test void wideLayoutHasRoomForFullSizeThreeLineTextAndBalancedTaskRail() {
        var wide = MonitorLayout.forViewport(616, 270);
        assertEquals(92, wide.cellWidth());
        assertTrue(wide.cellHeight() >= 3 * 10 + 4);
        assertEquals(54, wide.railWidth());
        int left = (616 - wide.width()) / 2;
        assertTrue(left >= wide.railWidth() + 4);
        assertTrue(left + wide.width() + 22 <= 616);
        var compact = MonitorLayout.forViewport(320, 240);
        assertEquals(0, compact.detailsWidth());
        assertEquals(254, compact.width());
        assertEquals(201, compact.height());
    }
    @Test void selectingCurrentTaskDoesNotTriggerNativeToggleOrSendAgain() {
        var monitor = mock(ICraftingMonitor.class);
        UUID current = UUID.randomUUID(), next = UUID.randomUUID();
        when(monitor.getTabSelected()).thenReturn(Optional.of(current));
        assertFalse(RsMonitorClient.select(monitor, current));
        verify(monitor, never()).onTabSelectionChanged(any());
        assertTrue(RsMonitorClient.select(monitor, next));
        verify(monitor).onTabSelectionChanged(Optional.of(next));
    }
    @Test void scrollAndDragReachBothEndsAndClampAfterTaskRemoval() {
        var state = new ListScrollState();
        state.range(200, 7);
        state.scroll(1000);
        assertEquals(193, state.offset());
        assertEquals(154 - state.thumbHeight(154), state.thumbTop(154));
        state.drag(-20, 154);
        assertEquals(0, state.offset());
        state.drag(1000, 154);
        assertEquals(193, state.offset());
        state.range(5, 7);
        assertEquals(0, state.offset());
        assertEquals(0, state.thumbTop(154));
        assertTrue(state.thumbHeight(154) > 0 && state.thumbHeight(154) < 154);
    }

    @Test void selectionCanRevealTheChosenTaskWithoutResettingUnrelatedScrolling() {
        var state = new ListScrollState();
        state.range(100, 7);
        state.reveal(80);
        assertEquals(74, state.offset());
        state.reveal(76);
        assertEquals(74, state.offset());
        state.reveal(2);
        assertEquals(2, state.offset());
        state.range(0, 4);
        state.drag(50, 120);
        assertEquals(0, state.offset());
    }

    @Test void onlyThePressedScrollbarDragsAndReleaseEndsTheGesture() {
        var state = new ListScrollState();
        state.range(100, 4);
        var changes = new AtomicInteger();
        var bar = new ListScrollBar(10, 20, ScrollBarStyle.WIDTH, 120, state, changes::incrementAndGet);
        assertFalse(bar.mouseDragged(12, 100, 0, 0, 50));
        bar.onClick(12, 22);
        assertEquals(0, changes.get(), "按住滑块时不应该跳行或重复刷新");
        assertTrue(bar.mouseDragged(12, 200, 0, 0, 178));
        assertEquals(state.maximum(), state.offset());
        assertTrue(bar.mouseReleased(12, 200, 0));
        assertFalse(bar.mouseDragged(12, 21, 0, 0, -179));
        assertEquals(1, changes.get());
    }

    @Test void disabledScrollbarDoesNotStartDraggingOrNotifyTheList() {
        var state = new ListScrollState();
        state.range(1, 4);
        var changes = new AtomicInteger();
        var bar = new ListScrollBar(10, 20, ScrollBarStyle.WIDTH, 120, state, changes::incrementAndGet);
        assertFalse(bar.mouseClicked(14, 22, 0));
        bar.onClick(14, 22);
        assertFalse(bar.mouseDragged(14, 100, 0, 0, 78));
        assertFalse(bar.mouseReleased(14, 100, 0));
        assertEquals(0, state.offset());
        assertEquals(0, changes.get());
    }

    @Test void trackClickAndDraggingRemainConsistentWhenResultsShrink() {
        var state = new ListScrollState();
        state.range(100, 4);
        var changes = new AtomicInteger();
        var bar = new ListScrollBar(10, 20, ScrollBarStyle.WIDTH, 120, state, changes::incrementAndGet);
        bar.onClick(14, 138);
        assertEquals(state.maximum(), state.offset());
        assertEquals(1, changes.get());
        state.range(2, 4);
        bar.mouseDragged(14, 50, 0, 0, -88);
        assertEquals(0, state.offset());
        assertFalse(bar.mouseDragged(14, 30, 0, 0, -20));
        assertEquals(1, changes.get(), "筛选后不足一页时停止拖拽，不再次触发列表更新");
    }
}
