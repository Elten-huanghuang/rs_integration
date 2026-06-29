package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Terminal Hub state machine.
 *
 * <p>When the number of bound machines exceeds {@link RSIntegrationConfig#MACHINE_TAB_THRESHOLD},
 * individual tabs collapse into a single "Hub" button.  Clicking it expands an overlay grid
 * showing all bound machines.</p>
 *
 * <p>States: {@code HIDDEN → ANIMATING_IN → VISIBLE → ANIMATING_OUT → HIDDEN}</p>
 */
public final class MachineHub {

    public enum State { HIDDEN, ANIMATING_IN, VISIBLE, ANIMATING_OUT }

    private static final long ANIM_DURATION_MS = 200;

    private static State state = State.HIDDEN;
    private static long stateEnteredAt;
    private static float animProgress; // 0..1
    private static final List<BindingInfo> machines = new ArrayList<>();
    private static final List<BindingInfo> filteredMachines = new ArrayList<>();
    private static int hoveredIndex = -1;
    private static boolean closeButtonHovered;
    private static String filterText = "";
    private static int scrollOffset;

    // ── Drag state ─────────────────────────────────────────────────
    private static int dragOffsetX, dragOffsetY;
    private static boolean isDragging;
    private static int dragStartMouseX, dragStartMouseY;
    private static int dragStartOffsetX, dragStartOffsetY;

    // store last-rendered hub bounds for title-bar hit testing
    private static int hubX, hubY, hubW, hubH;

    private MachineHub() {}

    // ── State queries ─────────────────────────────────────────────

    public static State getState() { return state; }
    public static float getAnimProgress() { return animProgress; }
    public static boolean isVisible() { return state == State.VISIBLE || state == State.ANIMATING_IN || state == State.ANIMATING_OUT; }
    public static int getHoveredIndex() { return hoveredIndex; }
    public static void setHoveredIndex(int idx) { hoveredIndex = idx; }
    public static boolean isCloseButtonHovered() { return closeButtonHovered; }
    public static void setCloseButtonHovered(boolean v) { closeButtonHovered = v; }
    public static List<BindingInfo> getMachines() { return filteredMachines; }
    public static List<BindingInfo> getAllMachines() { return machines; }
    public static String getFilterText() { return filterText; }
    public static int getScrollOffset() { return scrollOffset; }
    public static void setScrollOffset(int off) { scrollOffset = off; }

    // ── Drag ───────────────────────────────────────────────────────

    public static int getDragOffsetX() { return dragOffsetX; }
    public static int getDragOffsetY() { return dragOffsetY; }
    public static boolean isDragging() { return isDragging; }
    public static void setHubBounds(int x, int y, int w, int h) { hubX = x; hubY = y; hubW = w; hubH = h; }
    public static boolean isWithinBounds(int mouseX, int mouseY) {
        return mouseX >= hubX && mouseX < hubX + hubW && mouseY >= hubY && mouseY < hubY + hubH;
    }

    /** Start a drag from the title bar. */
    public static boolean tryStartDrag(double mouseX, double mouseY) {
        // title bar area: top PADDING + TITLE_H
        int titleBottom = hubY + 4 + 12 + 4; // PADDING + TITLE_H + margin
        if (mouseX >= hubX && mouseX < hubX + hubW
                && mouseY >= hubY && mouseY < titleBottom) {
            isDragging = true;
            dragStartMouseX = (int) mouseX;
            dragStartMouseY = (int) mouseY;
            dragStartOffsetX = dragOffsetX;
            dragStartOffsetY = dragOffsetY;
            return true;
        }
        return false;
    }

    public static void endDrag() { isDragging = false; }

    public static void updateDrag(int mouseX, int mouseY) {
        if (!isDragging) return;
        dragOffsetX = dragStartOffsetX + (mouseX - dragStartMouseX);
        dragOffsetY = dragStartOffsetY + (mouseY - dragStartMouseY);
    }

    public static void appendFilterChar(char c) {
        filterText += c;
        refilter();
    }

    public static void backspaceFilter() {
        if (filterText.isEmpty()) return;
        filterText = filterText.substring(0, filterText.length() - 1);
        refilter();
    }

    public static void clearFilter() {
        filterText = "";
        refilter();
    }

    private static void refilter() {
        filteredMachines.clear();
        if (filterText.isEmpty()) {
            filteredMachines.addAll(machines);
        } else {
            String lower = filterText.toLowerCase();
            for (var m : machines) {
                String localized = I18n.get(m.displayName()).toLowerCase();
                if (localized.contains(lower) || m.displayName().toLowerCase().contains(lower)) {
                    filteredMachines.add(m);
                }
            }
        }
        scrollOffset = 0;
        hoveredIndex = -1;
    }

    // ── State transitions ─────────────────────────────────────────

    /** Set the machine list and transition to ANIMATING_IN (or VISIBLE if already animating). */
    public static void show(List<BindingInfo> list) {
        machines.clear();
        var whitelist = RSIntegrationConfig.MACHINE_GUI_WHITELIST.get();
        for (var info : list) {
            ModType mt = ModType.fromBlockKey(info.blockKey());
            if (mt != null && !whitelist.contains(mt.id())) continue;
            machines.add(info);
        }
        if (state == State.VISIBLE) {
            refilter();
            return;
        }
        filterText = "";
        refilter();
        state = State.ANIMATING_IN;
        stateEnteredAt = System.currentTimeMillis();
        animProgress = 0f;
    }

    /** Start hide animation. */
    public static void hide() {
        if (state == State.HIDDEN) return;
        state = State.ANIMATING_OUT;
        stateEnteredAt = System.currentTimeMillis();
        animProgress = 1f;
    }

    /** Force immediate hide (no animation). */
    public static void hideImmediate() {
        state = State.HIDDEN;
        animProgress = 0f;
        machines.clear();
        filteredMachines.clear();
        filterText = "";
        scrollOffset = 0;
        hoveredIndex = -1;
    }

    /** Toggle visibility with animation. */
    public static void toggle(List<BindingInfo> list) {
        if (state == State.VISIBLE || state == State.ANIMATING_IN) {
            hide();
        } else {
            show(list);
        }
    }

    // ── Per-tick animation update ─────────────────────────────────

    /** Call once per client tick. Advances animation progress. */
    public static void tick() {
        switch (state) {
            case ANIMATING_IN -> {
                long elapsed = System.currentTimeMillis() - stateEnteredAt;
                animProgress = Mth.clamp((float) elapsed / ANIM_DURATION_MS, 0f, 1f);
                if (animProgress >= 1f) {
                    state = State.VISIBLE;
                    animProgress = 1f;
                }
            }
            case ANIMATING_OUT -> {
                long elapsed = System.currentTimeMillis() - stateEnteredAt;
                animProgress = Mth.clamp(1f - (float) elapsed / ANIM_DURATION_MS, 0f, 1f);
                if (animProgress <= 0f) {
                    state = State.HIDDEN;
                    animProgress = 0f;
                    machines.clear();
                    hoveredIndex = -1;
                }
            }
            default -> {}
        }
    }

    /** Check whether the Hub should be used instead of individual tabs. */
    public static boolean shouldUseHub(int machineCount) {
        if (machineCount == 0) return false;
        int threshold = RSIntegrationConfig.MACHINE_TAB_THRESHOLD.get();
        if (threshold == 0) return true; // 0 = always use Hub when machines exist
        return machineCount > threshold;
    }
}
