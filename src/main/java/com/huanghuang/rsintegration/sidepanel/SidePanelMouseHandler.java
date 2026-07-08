package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.sidepanel.client.SidePanelInputHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * Extracts mouse-event handling from {@link RSSidePanelClient}.
 * All methods are package-private static; they access RSSidePanelClient
 * fields directly (same package).
 */
final class SidePanelMouseHandler {

    private SidePanelMouseHandler() {}

    // ── Mouse press ───────────────────────────────────────────────

    @SuppressWarnings("resource")
    static void onScreenMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        var mc = Minecraft.getInstance();
        if (!RSSidePanelClient.panelVisible || mc.player == null) return;
        if (mc.screen != null && RSSidePanelClient.RS_SCREEN_CLASSES.contains(mc.screen.getClass().getName())) return;

        double mx = event.getMouseX(), my = event.getMouseY();
        int btn = event.getButton();

        if (RSSidePanelClient.panelHidden) {
            if (mx >= RSSidePanelClient.panelX && mx < RSSidePanelClient.panelX + RSSidePanelClient.GRID_W
                    && my >= RSSidePanelClient.panelY && my < RSSidePanelClient.panelY + RSSidePanelClient.HEADER_H) {
                if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    RSSidePanelClient.movingPanel = true;
                    RSSidePanelClient.moveStartMouseX = (int) mx;
                    RSSidePanelClient.moveStartMouseY = (int) my;
                    RSSidePanelClient.moveStartPanelX = RSSidePanelClient.panelX;
                    RSSidePanelClient.moveStartPanelY = RSSidePanelClient.panelY;
                } else if (btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    RSSidePanelClient.panelHidden = false;
                    RSSidePanelClient.savePosition();
                    SearchController.searchText = "";
                    if (SearchController.searchWidget != null) SearchController.searchWidget.setValue("");
                    SearchController.searchFocused = false;
                    RSSidePanelClient.scrollRow = 0;
                    RSSidePanelNetworkHandler.sendRequestSync();
                }
                event.setCanceled(true);
            }
            return;
        }

        if (!RSSidePanelClient.anyPanelContains(mx, my)) return;
        event.setCanceled(true);

        // Search bar
        if (RSSidePanelClient.searchBarContains(mx, my)) {
            if (!SearchController.searchFocused) SearchController.searchChangedSinceBlur = false;
            SearchController.searchFocused = true;
            if (btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                SearchController.searchWidget.setValue("");
                SearchController.searchText = "";
            } else {
                SearchController.searchWidget.mouseClicked(mx, my, btn);
            }
            return;
        }

        // Fold button
        int foldX = RSSidePanelClient.panelX + RSSidePanelClient.panelWidth() - 16;
        if (mx >= foldX && mx < foldX + 14 && my >= RSSidePanelClient.panelY + 2 && my < RSSidePanelClient.panelY + 16) {
            RSSidePanelClient.panelHidden = true;
            RSSidePanelClient.savePosition(); // persist hidden state
            SearchController.onSearchBlur();
            RSSidePanelNetworkHandler.sendCloseRequest();
            return;
        }

        // Header drag
        if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && RSSidePanelClient.headerContains(mx, my)
                && !RSSidePanelClient.searchBarContains(mx, my)) {
            RSSidePanelClient.movingPanel = true;
            RSSidePanelClient.moveStartMouseX = (int) mx;
            RSSidePanelClient.moveStartMouseY = (int) my;
            RSSidePanelClient.moveStartPanelX = RSSidePanelClient.panelX;
            RSSidePanelClient.moveStartPanelY = RSSidePanelClient.panelY;
            SearchController.onSearchBlur();
            return;
        }

        // Side buttons — use per-button bounds, not division
        if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int i = SidePanelInputHandler.sideButtonIndex(mx, my,
                    RSSidePanelClient.panelX, RSSidePanelClient.panelY);
            if (i >= 0) {
                RSSidePanelClient.handleSideButtonClick(i);
                SearchController.onSearchBlur();
                return;
            }
        }

        SearchController.onSearchBlur();

        if (!RSSidePanelClient.networkAvailable) return;

        // Scrollbar
        int scrollX = RSSidePanelClient.panelX + RSSidePanelClient.SCROLLBAR_X;
        int scrollY = RSSidePanelClient.panelY + RSSidePanelClient.HEADER_H;
        int scrollH = RSSidePanelClient.visibleRows * RSSidePanelClient.SLOT_SIZE;
        if (RSSidePanelClient.needsScrollbar() && mx >= scrollX && mx < scrollX + 12
                && my >= scrollY && my < scrollY + scrollH) {
            RSSidePanelClient.scrolling = true;
            RSSidePanelClient.updateScrollFromMouse(my);
            return;
        }

        // Grid slots
        int itemLeft = RSSidePanelClient.panelX + RSSidePanelClient.GRID_ITEM_X;
        int itemTop  = RSSidePanelClient.panelY + RSSidePanelClient.HEADER_H;
        if (mx < itemLeft || mx >= itemLeft + RSSidePanelClient.COLUMNS * RSSidePanelClient.SLOT_SIZE
                || my < itemTop || my >= itemTop + RSSidePanelClient.visibleRows * RSSidePanelClient.SLOT_SIZE) return;

        int col = ((int) mx - itemLeft) / RSSidePanelClient.SLOT_SIZE;
        int row = ((int) my - itemTop) / RSSidePanelClient.SLOT_SIZE;
        if (col < 0 || col >= RSSidePanelClient.COLUMNS || row < 0 || row >= RSSidePanelClient.visibleRows) return;

        // GUI icon click — open bound machine
        if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int ix = itemLeft + col * RSSidePanelClient.SLOT_SIZE + 1;
            int iy = itemTop + row * RSSidePanelClient.SLOT_SIZE + 1;
            int guiIconX = ix + 10;
            int guiIconY = iy + 10;
            if (mx >= guiIconX && mx < guiIconX + 8 && my >= guiIconY && my < guiIconY + 8) {
                int dIdx = (RSSidePanelClient.scrollRow + row) * RSSidePanelClient.COLUMNS + col;
                if (dIdx >= 0 && dIdx < RSSidePanelClient.displayList.size()) {
                    PanelStack ps = RSSidePanelClient.displayList.get(dIdx);
                    if (ps != null) {
                        String itemKey = RSSidePanelClient.keyOf(ps.getStack());
                        var info = com.huanghuang.rsintegration.sidepanel.data.BindingCache.getInstance().getBinding(itemKey);
                        if (info != null) {
                            com.huanghuang.rsintegration.sidepanel.client.GuiNavStack.pushCurrent();
                            RSSidePanelNetworkHandler.CHANNEL.sendToServer(
                                    new com.huanghuang.rsintegration.sidepanel.network.OpenBoundMachineGuiPacket(
                                            info.dim(), info.pos(), itemKey));
                            return;
                        }
                    }
                }
            }
        }

        // Insert carried item
        ItemStack carried = mc.player.containerMenu.getCarried();
        if (!carried.isEmpty()) {
            handleInsert(carried, btn, mc);
            return;
        }

        // Extract / drag from occupied slot
        handleExtract(col, row, btn);
    }

    private static void handleInsert(ItemStack carried, int btn, Minecraft mc) {
        if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            RSSidePanelNetworkHandler.sendInsert(carried, false);
            mc.player.containerMenu.setCarried(ItemStack.EMPTY);
        } else if (btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            RSSidePanelNetworkHandler.sendInsert(carried, true);
            carried.shrink(1);
            if (carried.isEmpty()) {
                mc.player.containerMenu.setCarried(ItemStack.EMPTY);
            }
        }
        int insertCount = btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? 1 : carried.getCount();
        String ck = RSSidePanelClient.keyOf(carried);
        if (!ck.isEmpty() && insertCount > 0) {
            UUID existingId = null;
            for (PanelStack p : RSSidePanelClient.panels) {
                if (p.searchKey().equals(ck)) { existingId = p.getId(); break; }
            }
            if (existingId != null) {
                PanelStack cur = RSSidePanelClient.getById(existingId);
                if (cur != null) {
                    cur.grow(insertCount);
                    cur.timestamp = System.currentTimeMillis();
                    RSSidePanelClient.recordSlotAnim(existingId, insertCount);
                    RSSidePanelClient.displayDirty = true;
                }
            }
        }
    }

    private static void handleExtract(int col, int row, int btn) {
        int dIdx = (RSSidePanelClient.scrollRow + row) * RSSidePanelClient.COLUMNS + col;
        if (dIdx < 0 || dIdx >= RSSidePanelClient.displayList.size()) return;
        PanelStack clickedPs = RSSidePanelClient.displayList.get(dIdx);
        if (clickedPs == null || clickedPs.zeroed || clickedPs.getStack().isEmpty()) return;

        ItemStack clickedItem = clickedPs.getStack();
        byte action;
        int extractCount;
        if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
            action = RSSidePanelClickPacket.ACTION_EXTRACT_MAX;
            extractCount = Math.min(clickedItem.getMaxStackSize(), clickedPs.getCount());
        } else if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            action = RSSidePanelClickPacket.ACTION_EXTRACT_ONE;
            extractCount = 1;
        } else {
            action = RSSidePanelClickPacket.ACTION_EXTRACT_STACK;
            extractCount = Math.max(1, clickedPs.getCount() / 2);
        }
        RSSidePanelNetworkHandler.sendClick(clickedItem, action,
                net.minecraft.client.gui.screens.Screen.hasShiftDown(), clickedPs.getId());

        if (extractCount > 0) {
            UUID pk = clickedPs.getId();
            RSSidePanelClient.pendingExtractions.put(pk,
                    new RSSidePanelClient.PendingExtraction(clickedPs.getStack(), clickedPs.timestamp, clickedPs.craftable));
            RSSidePanelClient.recordSlotAnim(pk, -extractCount);
            int remaining = clickedPs.getCount() - extractCount;
            if (remaining <= 0) {
                clickedPs.setCount(0);
            } else {
                clickedPs.setCount(remaining);
            }
            clickedPs.timestamp = System.currentTimeMillis();
            RSSidePanelClient.displayDirty = true;
        }
    }

    // ── Mouse release ─────────────────────────────────────────────

    @SuppressWarnings("resource")
    static void onScreenMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (RSSidePanelClient.movingPanel) {
            RSSidePanelClient.movingPanel = false;
            if (RSSidePanelClient.panelHidden) {
                int dx = (int) event.getMouseX() - RSSidePanelClient.moveStartMouseX;
                int dy = (int) event.getMouseY() - RSSidePanelClient.moveStartMouseY;
                if (Math.abs(dx) < 3 && Math.abs(dy) < 3) {
                    RSSidePanelClient.panelHidden = false;
                    RSSidePanelClient.savePosition();
                    SearchController.searchText = "";
                    if (SearchController.searchWidget != null) SearchController.searchWidget.setValue("");
                    SearchController.searchFocused = false;
                    RSSidePanelClient.scrollRow = 0;
                    RSSidePanelNetworkHandler.sendRequestSync();
                }
            }
            RSSidePanelClient.savePosition();
            event.setCanceled(true);
            return;
        }
        if (RSSidePanelClient.scrolling) {
            RSSidePanelClient.scrolling = false;
            event.setCanceled(true);
            return;
        }
        if (RSSidePanelClient.gridDragging) {
            handleDragRelease();
            RSSidePanelClient.gridDragging = false;
            RSSidePanelClient.gridDragKeys.clear();
            event.setCanceled(true);
            return;
        }

        if (!RSSidePanelClient.panelVisible || RSSidePanelClient.panelHidden) return;
        if (RSSidePanelClient.anyPanelContains(event.getMouseX(), event.getMouseY()))
            event.setCanceled(true);
    }

    private static void handleDragRelease() {
        if (RSSidePanelClient.gridDragKeys.size() > 1 || RSSidePanelClient.gridDragCrossedSlots) {
            List<ItemStack> dragItems = new ArrayList<>();
            for (UUID key : RSSidePanelClient.gridDragKeys) {
                PanelStack ps = RSSidePanelClient.getById(key);
                if (ps != null && !ps.getStack().isEmpty()) dragItems.add(ps.getStack());
            }
            if (!dragItems.isEmpty()) {
                RSSidePanelNetworkHandler.sendDragDistribute(dragItems);
                for (UUID key : RSSidePanelClient.gridDragKeys) {
                    PanelStack ps = RSSidePanelClient.getById(key);
                    if (ps != null && ps.getCount() > 0) {
                        RSSidePanelClient.pendingExtractions.put(key,
                                new RSSidePanelClient.PendingExtraction(ps.getStack(), ps.timestamp, ps.craftable));
                        RSSidePanelClient.recordSlotAnim(key, -1);
                        int remaining = ps.getCount() - 1;
                        if (remaining <= 0) ps.setCount(0);
                        else ps.setCount(remaining);
                        ps.timestamp = System.currentTimeMillis();
                    }
                }
                RSSidePanelClient.displayDirty = true;
            }
        } else if (!RSSidePanelClient.gridDragKeys.isEmpty()) {
            UUID key = RSSidePanelClient.gridDragKeys.iterator().next();
            PanelStack ps = RSSidePanelClient.getById(key);
            if (ps != null) {
                int extractCount = Math.min(ps.getStack().getMaxStackSize(), ps.getCount());
                RSSidePanelNetworkHandler.sendClick(ps.getStack(),
                        RSSidePanelClickPacket.ACTION_EXTRACT_MAX, false, ps.getId());
                RSSidePanelClient.pendingExtractions.put(key,
                        new RSSidePanelClient.PendingExtraction(ps.getStack(), ps.timestamp, ps.craftable));
                RSSidePanelClient.recordSlotAnim(key, -extractCount);
                int remaining = ps.getCount() - extractCount;
                if (remaining <= 0) ps.setCount(0);
                else ps.setCount(remaining);
                ps.timestamp = System.currentTimeMillis();
                RSSidePanelClient.displayDirty = true;
            }
        }
    }

    // ── Mouse dragged ─────────────────────────────────────────────

    @SuppressWarnings("resource")
    static void onScreenMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!RSSidePanelClient.panelVisible) return;
        if (RSSidePanelClient.panelHidden && !RSSidePanelClient.movingPanel) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        double mx = event.getMouseX(), my = event.getMouseY();
        if (!(RSSidePanelClient.movingPanel || RSSidePanelClient.scrolling
                || RSSidePanelClient.gridDragging || RSSidePanelClient.anyPanelContains(mx, my)))
            return;
        event.setCanceled(true);

        if (RSSidePanelClient.movingPanel) {
            int sw = mc.getWindow().getGuiScaledWidth();
            int sh = mc.getWindow().getGuiScaledHeight();
            int pw = RSSidePanelClient.panelWidth(), ph = RSSidePanelClient.panelHeight();
            RSSidePanelClient.panelX = net.minecraft.util.Mth.clamp(
                    RSSidePanelClient.moveStartPanelX + ((int) mx - RSSidePanelClient.moveStartMouseX), 0, sw - pw);
            RSSidePanelClient.panelY = net.minecraft.util.Mth.clamp(
                    RSSidePanelClient.moveStartPanelY + ((int) my - RSSidePanelClient.moveStartMouseY), 0, sh - ph);
            return;
        }

        if (RSSidePanelClient.scrolling) {
            RSSidePanelClient.updateScrollFromMouse(my);
            return;
        }

        if (RSSidePanelClient.gridDragging) {
            int itemLeft = RSSidePanelClient.panelX + RSSidePanelClient.GRID_ITEM_X;
            int itemTop  = RSSidePanelClient.panelY + RSSidePanelClient.HEADER_H;
            int col = ((int) mx - itemLeft) / RSSidePanelClient.SLOT_SIZE;
            int row = ((int) my - itemTop) / RSSidePanelClient.SLOT_SIZE;
            if (col >= 0 && col < RSSidePanelClient.COLUMNS && row >= 0 && row < RSSidePanelClient.visibleRows) {
                int dIdx = (RSSidePanelClient.scrollRow + row) * RSSidePanelClient.COLUMNS + col;
                if (dIdx >= 0 && dIdx < RSSidePanelClient.displayList.size()) {
                    PanelStack ps = RSSidePanelClient.displayList.get(dIdx);
                    if (ps != null && !ps.getStack().isEmpty()) {
                        UUID k = ps.getId();
                        if (!RSSidePanelClient.gridDragKeys.contains(k))
                            RSSidePanelClient.gridDragCrossedSlots = true;
                        RSSidePanelClient.gridDragKeys.add(k);
                    }
                }
            }
        }
    }

    // ── Scroll wheel ──────────────────────────────────────────────

    @SuppressWarnings("resource")
    static void onScreenMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!RSSidePanelClient.panelVisible || RSSidePanelClient.panelHidden) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen == null) return;

        double mx = event.getMouseX(), my = event.getMouseY();
        if (!RSSidePanelClient.anyPanelContains(mx, my)) return;
        event.setCanceled(true);
        applyScrollDelta(event.getScrollDelta());
    }

    @SuppressWarnings("resource")
    static void onInputMouseScrolled(InputEvent.MouseScrollingEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !RSSidePanelClient.panelVisible || RSSidePanelClient.panelHidden) return;
        if (mc.screen != null) return;

        double mx = RSSidePanelClient.lastMouseX, my = RSSidePanelClient.lastMouseY;
        if (!RSSidePanelClient.anyPanelContains(mx, my)) return;
        event.setCanceled(true);
        applyScrollDelta(event.getScrollDelta());
    }

    private static void applyScrollDelta(double delta) {
        if (delta < 0) RSSidePanelClient.scrollRow++;
        else if (delta > 0) RSSidePanelClient.scrollRow--;
        RSSidePanelClient.clampScroll();
    }
}
