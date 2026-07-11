package com.huanghuang.rsintegration.machine;

import com.github.stuxuhai.jpinyin.PinyinFormat;
import com.github.stuxuhai.jpinyin.PinyinHelper;
import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabHandler;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;

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
 *
 * <p>This class uses a singleton holder pattern: state is stored in instance fields rather than
 * static fields to prevent cross-server contamination when joining different worlds in succession.
 * Public static methods remain for backwards compatibility and delegate to the singleton.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class MachineHub {

    public enum State { HIDDEN, ANIMATING_IN, VISIBLE, ANIMATING_OUT }

    private static final long ANIM_DURATION_MS = 200;

    // ── Singleton holder ──────────────────────────────────────────

    private static final MachineHub INSTANCE = new MachineHub();

    /** Returns the singleton instance. Prefer this for new internal callers;
     *  existing callers may continue using the static delegation methods. */
    public static MachineHub getInstance() { return INSTANCE; }

    // ── Instance state ────────────────────────────────────────────

    private State state = State.HIDDEN;
    private long stateEnteredAt;
    private float animProgress; // 0..1
    private final List<BindingInfo> machines = new ArrayList<>();
    private final List<BindingInfo> filteredMachines = new ArrayList<>();
    private int hoveredIndex = -1;
    private boolean closeButtonHovered;
    private String filterText = "";
    private int scrollOffset;

    // ── Drag state ─────────────────────────────────────────────────
    private int dragOffsetX, dragOffsetY;
    private boolean isDragging;
    private int dragStartMouseX, dragStartMouseY;
    private int dragStartOffsetX, dragStartOffsetY;

    // store last-rendered hub bounds for title-bar hit testing
    private int hubX, hubY, hubW, hubH;

    // Tooltip bleed guard — set while the Hub is rendering its own tooltips.
    // Kept static because it is cross-cutting (per-frame ephemeral, accessed
    // from RSSidePanelClient and GridScreenTooltipMixin for tooltip suppression).
    public static volatile boolean isRenderingOurTooltip;

    private MachineHub() {}

    static {
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    // ── State queries ─────────────────────────────────────────────

    public static State getState() { return INSTANCE.state; }
    public static float getAnimProgress() { return INSTANCE.animProgress; }
    public static boolean isVisible() {
        return INSTANCE.state == State.VISIBLE
                || INSTANCE.state == State.ANIMATING_IN
                || INSTANCE.state == State.ANIMATING_OUT;
    }
    public static int getHoveredIndex() { return INSTANCE.hoveredIndex; }
    public static void setHoveredIndex(int idx) { INSTANCE.hoveredIndex = idx; }
    public static boolean isCloseButtonHovered() { return INSTANCE.closeButtonHovered; }
    public static void setCloseButtonHovered(boolean v) { INSTANCE.closeButtonHovered = v; }
    public static List<BindingInfo> getMachines() { return INSTANCE.filteredMachines; }
    public static List<BindingInfo> getAllMachines() { return INSTANCE.machines; }
    public static String getFilterText() { return INSTANCE.filterText; }
    public static int getScrollOffset() { return INSTANCE.scrollOffset; }
    public static void setScrollOffset(int off) { INSTANCE.scrollOffset = off; }

    // ── Drag ───────────────────────────────────────────────────────

    public static int getDragOffsetX() { return INSTANCE.dragOffsetX; }
    public static int getDragOffsetY() { return INSTANCE.dragOffsetY; }
    public static boolean isDragging() { return INSTANCE.isDragging; }
    public static void setHubBounds(int x, int y, int w, int h) {
        INSTANCE.hubX = x; INSTANCE.hubY = y; INSTANCE.hubW = w; INSTANCE.hubH = h;
    }
    public static boolean isWithinBounds(int mouseX, int mouseY) {
        return mouseX >= INSTANCE.hubX && mouseX < INSTANCE.hubX + INSTANCE.hubW
                && mouseY >= INSTANCE.hubY && mouseY < INSTANCE.hubY + INSTANCE.hubH;
    }

    /** Start a drag from the title bar. */
    public static boolean tryStartDrag(double mouseX, double mouseY) {
        int titleBottom = INSTANCE.hubY + 4 + 12 + 4;
        if (mouseX >= INSTANCE.hubX && mouseX < INSTANCE.hubX + INSTANCE.hubW
                && mouseY >= INSTANCE.hubY && mouseY < titleBottom) {
            INSTANCE.isDragging = true;
            INSTANCE.dragStartMouseX = (int) mouseX;
            INSTANCE.dragStartMouseY = (int) mouseY;
            INSTANCE.dragStartOffsetX = INSTANCE.dragOffsetX;
            INSTANCE.dragStartOffsetY = INSTANCE.dragOffsetY;
            return true;
        }
        return false;
    }

    public static void endDrag() { INSTANCE.isDragging = false; }

    public static void updateDrag(int mouseX, int mouseY) {
        if (!INSTANCE.isDragging) return;
        INSTANCE.dragOffsetX = INSTANCE.dragStartOffsetX + (mouseX - INSTANCE.dragStartMouseX);
        INSTANCE.dragOffsetY = INSTANCE.dragStartOffsetY + (mouseY - INSTANCE.dragStartMouseY);
    }

    public static void appendFilterChar(char c) {
        INSTANCE.filterText += c;
        INSTANCE.refilter();
    }

    public static void backspaceFilter() {
        if (INSTANCE.filterText.isEmpty()) return;
        INSTANCE.filterText = INSTANCE.filterText.substring(0, INSTANCE.filterText.length() - 1);
        INSTANCE.refilter();
    }

    public static void clearFilter() {
        INSTANCE.filterText = "";
        INSTANCE.refilter();
    }

    private void refilter() {
        filteredMachines.clear();
        if (filterText.isEmpty()) {
            filteredMachines.addAll(machines);
        } else {
            String lower = filterText.toLowerCase();
            for (var m : machines) {
                String localized = I18n.get(m.displayName()).toLowerCase();
                if (localized.contains(lower) || m.displayName().toLowerCase().contains(lower)) {
                    filteredMachines.add(m);
                    continue;
                }
                // Pinyin search (same as side panel DisplayListManager)
                if (matchesPinyin(I18n.get(m.displayName()), lower)) {
                    filteredMachines.add(m);
                    continue;
                }
                // Also search the client-side resolved name — cover gun-pack
                // workbench names even when BlockId is nested in BlockEntityTag.
                var ds = m.displayStack();
                if (ds != null && !ds.isEmpty()) {
                    String resolved = BindingEventHandler
                            .resolveBlockName(m.blockKey(), m.blockRegKey(), ds)
                            .getString().toLowerCase();
                    if (resolved.contains(lower) || matchesPinyin(resolved, lower)) {
                        filteredMachines.add(m);
                    }
                }
            }
        }
        scrollOffset = 0;
        hoveredIndex = -1;
    }

    private static boolean matchesPinyin(String text, String lowerQuery) {
        try {
            String pinyin = PinyinHelper.convertToPinyinString(text, "",
                    PinyinFormat.WITHOUT_TONE);
            if (pinyin.toLowerCase().contains(lowerQuery)) return true;
            String shortPinyin = PinyinHelper.getShortPinyin(text);
            if (shortPinyin != null && shortPinyin.toLowerCase().contains(lowerQuery))
                return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ── State transitions ─────────────────────────────────────────

    /** Set the machine list and transition to ANIMATING_IN (or VISIBLE if already animating). */
    public static void show(List<BindingInfo> list) {
        INSTANCE.machines.clear();
        INSTANCE.machines.addAll(list);
        if (INSTANCE.state == State.VISIBLE) {
            INSTANCE.refilter();
            return;
        }
        INSTANCE.filterText = "";
        INSTANCE.refilter();
        INSTANCE.state = State.ANIMATING_IN;
        INSTANCE.stateEnteredAt = System.currentTimeMillis();
        INSTANCE.animProgress = 0.001f;
    }

    /** Refresh machine list from BindingCache without animation. */
    public static void refreshMachines() {
        if (INSTANCE.state != State.VISIBLE && INSTANCE.state != State.HIDDEN) return;
        INSTANCE.machines.clear();
        INSTANCE.machines.addAll(MachineTabHandler.getAllMachines());
        INSTANCE.refilter();
        if (INSTANCE.machines.isEmpty() && INSTANCE.state == State.VISIBLE) {
            hide();
        }
    }

    /** Start hide animation. */
    public static void hide() {
        if (INSTANCE.state == State.HIDDEN) return;
        INSTANCE.state = State.ANIMATING_OUT;
        INSTANCE.stateEnteredAt = System.currentTimeMillis();
        INSTANCE.animProgress = 1f;
    }

    /** Force immediate hide (no animation). */
    public static void hideImmediate() {
        INSTANCE.state = State.HIDDEN;
        INSTANCE.animProgress = 0f;
        INSTANCE.machines.clear();
        INSTANCE.filteredMachines.clear();
        INSTANCE.filterText = "";
        INSTANCE.scrollOffset = 0;
        INSTANCE.hoveredIndex = -1;
    }

    /** Clear all state on logout to prevent cross-server contamination. */
    @SubscribeEvent
    public void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        hideImmediate();
    }

    /** Toggle visibility with animation. */
    public static void toggle(List<BindingInfo> list) {
        if (INSTANCE.state == State.VISIBLE || INSTANCE.state == State.ANIMATING_IN) {
            hide();
        } else {
            show(list);
        }
    }

    // ── Per-tick animation update ─────────────────────────────────

    /** Call once per client tick. Advances animation progress. */
    public static void tick() {
        switch (INSTANCE.state) {
            case ANIMATING_IN -> {
                long elapsed = System.currentTimeMillis() - INSTANCE.stateEnteredAt;
                INSTANCE.animProgress = Mth.clamp((float) elapsed / ANIM_DURATION_MS, 0f, 1f);
                if (INSTANCE.animProgress >= 1f) {
                    INSTANCE.state = State.VISIBLE;
                    INSTANCE.animProgress = 1f;
                }
            }
            case ANIMATING_OUT -> {
                long elapsed = System.currentTimeMillis() - INSTANCE.stateEnteredAt;
                INSTANCE.animProgress = Mth.clamp(1f - (float) elapsed / ANIM_DURATION_MS, 0f, 1f);
                if (INSTANCE.animProgress <= 0f) {
                    INSTANCE.state = State.HIDDEN;
                    INSTANCE.animProgress = 0f;
                    INSTANCE.machines.clear();
                    INSTANCE.hoveredIndex = -1;
                }
            }
            default -> {}
        }
    }

    /** Check whether the Hub should be used instead of individual tabs. */
    public static boolean shouldUseHub(int machineCount) {
        if (machineCount == 0) return false;
        int threshold = ClientSyncedConfig.isSynced()
                ? ClientSyncedConfig.MACHINE_TAB_THRESHOLD
                : RSIntegrationConfig.MACHINE_TAB_THRESHOLD.get();
        if (threshold == 0) return true;
        return machineCount > threshold;
    }
}
