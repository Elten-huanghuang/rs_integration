package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.sidepanel.RSSidePanelClient;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * Input-handling utilities for the side panel.
 * Extracts hit-testing, keyboard translation, and scrollbar logic
 * from {@link RSSidePanelClient} into pure static helpers.
 */
public final class SidePanelInputHandler {

    // Re-export layout constants used by hit-testing so callers
    // (and the renderer) can reference them from one place.
    public static final int GRID_W           = RSSidePanelClient.GRID_W;
    public static final int HEADER_H         = RSSidePanelClient.HEADER_H;
    public static final int SIDE_BTN_SIZE    = RSSidePanelClient.SIDE_BTN_SIZE;
    public static final int SIDE_BTN_GAP     = RSSidePanelClient.SIDE_BTN_GAP;
    public static final int SIDE_BTN_PITCH   = SIDE_BTN_SIZE + SIDE_BTN_GAP;
    public static final int SIDE_BTN_FLOAT_X = RSSidePanelClient.SIDE_BTN_FLOAT_X;
    public static final int SCROLLBAR_X      = RSSidePanelClient.SCROLLBAR_X;
    public static final int SLOT_SIZE        = RSSidePanelClient.SLOT_SIZE;
    public static final int COLUMNS          = RSSidePanelClient.COLUMNS;
    public static final int GRID_ITEM_X      = RSSidePanelClient.GRID_ITEM_X;
    public static final int BOTTOM_H         = RSSidePanelClient.BOTTOM_H;

    private SidePanelInputHandler() {}

    // ── Hit-testing ──────────────────────────────────────────────

    /** Check whether the given screen coordinate falls within the panel's interactive area. */
    public static boolean anyPanelContains(double mx, double my,
                                            int panelX, int panelY,
                                            int visibleRows, boolean panelHidden) {
        int pw = GRID_W;
        int rows = visibleRows > 0 ? visibleRows : 5;
        int ph = HEADER_H + rows * SLOT_SIZE + BOTTOM_H;
        if (mx >= panelX && mx < panelX + pw
                && my >= panelY && my < panelY + ph)
            return true;
        if (mx >= panelX + SIDE_BTN_FLOAT_X && mx < panelX
                && my >= panelY + HEADER_H + 1
                && my < panelY + HEADER_H + 1 + 5 * SIDE_BTN_PITCH)
            return true;
        if (panelHidden && mx >= panelX && mx < panelX + GRID_W
                && my >= panelY && my < panelY + HEADER_H)
            return true;
        return false;
    }

    /** Check whether the coordinate is within the floating side-button column. */
    public static boolean sideButtonContains(double mx, double my,
                                              int panelX, int panelY) {
        int bx = panelX + SIDE_BTN_FLOAT_X;
        int by = panelY + HEADER_H + 1;
        return mx >= bx && mx < bx + SIDE_BTN_SIZE
                && my >= by && my < by + 5 * SIDE_BTN_PITCH;
    }

    /** Check whether the coordinate hits a specific side button (0-4).
     *  Uses the button's actual 18×18 bounds, not the 20-pitch cell,
     *  so the 2 px gap between buttons is dead space. */
    public static int sideButtonIndex(double mx, double my, int panelX, int panelY) {
        int bx = panelX + SIDE_BTN_FLOAT_X;
        int by = panelY + HEADER_H + 1;
        if (mx < bx || mx >= bx + SIDE_BTN_SIZE) return -1;
        for (int i = 0; i < 5; i++) {
            int btnY = by + i * SIDE_BTN_PITCH;
            if (my >= btnY && my < btnY + SIDE_BTN_SIZE) return i;
        }
        return -1;
    }

    /** Check whether the coordinate is within the search bar. */
    public static boolean searchBarContains(double mx, double my,
                                             int panelX, int panelY) {
        int sx = panelX + 81, sy = panelY + 7;
        return mx >= sx && mx < sx + 82 && my >= sy && my < sy + 9;
    }

    /** Check whether the coordinate is within the header strip (excluding search bar). */
    public static boolean headerContains(double mx, double my,
                                          int panelX, int panelY) {
        return mx >= panelX && mx < panelX + GRID_W
                && my >= panelY && my < panelY + HEADER_H;
    }

    // ── Scrollbar helpers ────────────────────────────────────────

    /**
     * Compute the new scroll-row from a mouse Y position on the scrollbar track.
     *
     * @param my            mouse Y coordinate (gui-scaled)
     * @param panelY        panel top Y
     * @param visibleRows   number of visible rows
     * @param totalRows     total content rows
     * @param currentScroll current scroll row
     * @return new scroll row (clamped)
     */
    public static int updateScrollFromMouse(double my, int panelY,
                                             int visibleRows, int totalRows,
                                             int currentScroll) {
        int maxScroll = Math.max(0, totalRows - visibleRows);
        if (maxScroll <= 0) return 0;
        int trackY = panelY + HEADER_H;
        int trackH = visibleRows * SLOT_SIZE;
        double t = (my - trackY) / Math.max(1, trackH);
        t = Mth.clamp(t, 0, 1);
        int scrollRow = (int) Math.round(t * maxScroll);
        return clampScroll(scrollRow, totalRows, visibleRows);
    }

    /** Clamp scrollRow to valid range. */
    public static int clampScroll(int scrollRow, int totalRows, int visibleRows) {
        int max = Math.max(0, totalRows - visibleRows);
        if (scrollRow < 0) scrollRow = 0;
        if (scrollRow > max) scrollRow = max;
        return scrollRow;
    }

    // ── Keyboard helpers ─────────────────────────────────────────

    /** Translate a GLFW key+scancode to a character string for text input.
     *  Returns null when the key does not produce a text character. */
    public static String glfwKeyToChar(int key, int scanCode) {
        String cn = GLFW.glfwGetKeyName(key, scanCode);
        if (cn != null && cn.length() == 1)
            return Screen.hasShiftDown() ? cn.toUpperCase() : cn;
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
            char c = (char) ('a' + (key - GLFW.GLFW_KEY_A));
            return String.valueOf(Screen.hasShiftDown() ? Character.toUpperCase(c) : c);
        }
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9 && !Screen.hasShiftDown())
            return String.valueOf((char) ('0' + (key - GLFW.GLFW_KEY_0)));
        if (key == GLFW.GLFW_KEY_SPACE) return " ";
        if (key == GLFW.GLFW_KEY_MINUS) return Screen.hasShiftDown() ? "_" : "-";
        if (key == GLFW.GLFW_KEY_PERIOD) return ".";
        if (key == GLFW.GLFW_KEY_SLASH) return Screen.hasShiftDown() ? "?" : "/";
        if (key == GLFW.GLFW_KEY_EQUAL) return Screen.hasShiftDown() ? "+" : "=";
        if (key == GLFW.GLFW_KEY_APOSTROPHE) return Screen.hasShiftDown() ? "\"" : "'";
        if (key == GLFW.GLFW_KEY_COMMA) return Screen.hasShiftDown() ? "<" : ",";
        if (key == GLFW.GLFW_KEY_SEMICOLON) return Screen.hasShiftDown() ? ":" : ";";
        if (key == GLFW.GLFW_KEY_LEFT_BRACKET) return Screen.hasShiftDown() ? "{" : "[";
        if (key == GLFW.GLFW_KEY_RIGHT_BRACKET) return Screen.hasShiftDown() ? "}" : "]";
        if (key == GLFW.GLFW_KEY_BACKSLASH) return Screen.hasShiftDown() ? "|" : "\\";
        if (key == GLFW.GLFW_KEY_GRAVE_ACCENT) return Screen.hasShiftDown() ? "~" : "`";
        return null;
    }

    /**
     * Cycle a side-button index to its next mode/value.
     *
     * @return a {@link SideButtonResult} with the new values after cycling
     */
    public static SideButtonResult cycleSideButton(int idx,
                                                    int viewType, boolean sortAsc,
                                                    int sortMode, int searchMode,
                                                    int gridSize, int scrollRow) {
        int newViewType = viewType;
        boolean newSortAsc = sortAsc;
        int newSortMode = sortMode;
        int newSearchMode = searchMode;
        int newGridSize = gridSize;
        int newScrollRow = scrollRow;
        int prevSearchMode = searchMode;
        boolean needsJeiClear = false;
        boolean needsJeiPull = false;

        switch (idx) {
            case 0:
                newViewType = (viewType + 1) % 3;
                newScrollRow = 0;
                break;
            case 1:
                newSortAsc = !sortAsc;
                break;
            case 2:
                newSortMode = (sortMode + 1) % 4;
                break;
            case 3:
                newSearchMode = (searchMode + 1) % 6;
                if (newSearchMode >= 2 && !SidePanelJeiBridge.isJeiAvailable())
                    newSearchMode = 0;
                if (prevSearchMode < 2 && newSearchMode >= 2)
                    needsJeiPull = true;
                else if (prevSearchMode >= 2 && newSearchMode < 2)
                    needsJeiClear = true;
                break;
            case 4:
                newGridSize = (gridSize + 1) % 4;
                newScrollRow = 0;
                break;
        }

        return new SideButtonResult(newViewType, newSortAsc, newSortMode,
                newSearchMode, newGridSize, newScrollRow, needsJeiPull, needsJeiClear);
    }

    // ── Result type ──────────────────────────────────────────────

    /** Result returned by {@link #cycleSideButton}. */
    public static final class SideButtonResult {
        public final int viewType;
        public final boolean sortAsc;
        public final int sortMode;
        public final int searchMode;
        public final int gridSize;
        public final int scrollRow;
        public final boolean needsJeiPull;
        public final boolean needsJeiClear;

        SideButtonResult(int viewType, boolean sortAsc, int sortMode,
                         int searchMode, int gridSize, int scrollRow,
                         boolean needsJeiPull, boolean needsJeiClear) {
            this.viewType = viewType;
            this.sortAsc = sortAsc;
            this.sortMode = sortMode;
            this.searchMode = searchMode;
            this.gridSize = gridSize;
            this.scrollRow = scrollRow;
            this.needsJeiPull = needsJeiPull;
            this.needsJeiClear = needsJeiClear;
        }
    }
}
