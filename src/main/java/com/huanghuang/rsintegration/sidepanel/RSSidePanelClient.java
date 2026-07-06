package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.registries.ForgeRegistries;

import com.huanghuang.rsintegration.sidepanel.client.SidePanelInputHandler;
import com.huanghuang.rsintegration.sidepanel.client.SidePanelJeiBridge;
import com.huanghuang.rsintegration.sidepanel.client.SidePanelPreferences;
import com.huanghuang.rsintegration.sidepanel.client.SidePanelRenderer;
import com.huanghuang.rsintegration.sidepanel.model.PanelDataModel;
import java.util.*;

@OnlyIn(Dist.CLIENT)
public final class RSSidePanelClient {

    // ── Layout constants ──────────────────────────────────────────
    public static final int SLOT_SIZE        = 18;
    public static final int COLUMNS          = 9;
    public static final int GRID_W           = 193;
    public static final int HEADER_H         = 19;
    public static final int BOTTOM_H         = 7;
    public static final int GRID_ITEM_X      = 8;
    public static final int SCROLLBAR_X      = 174;
    public static final int SIDE_BTN_SIZE  = 18;
    public static final int SIDE_BTN_GAP   = 2;
    public static final int SIDE_BTN_PITCH = SIDE_BTN_SIZE + SIDE_BTN_GAP;
    public static final int SIDE_BTN_FLOAT_X = -20;

    public static final ResourceLocation RS_GRID_TEX =
            new ResourceLocation("refinedstorage", "textures/gui/grid.png");
    public static final ResourceLocation RS_ICONS_TEX =
            new ResourceLocation(RSIntegrationMod.MOD_ID, "textures/gui/icons.png");

    static final Set<String> RS_SCREEN_CLASSES = Set.of(
            "com.refinedmods.refinedstorage.screen.grid.GridScreen",
            "com.refinedmods.refinedstorage.screen.CraftingMonitorScreen",
            "com.refinedmods.refinedstorage.screen.ControllerScreen"
    );

    // ── UI state ─────────────────────────────────────────────────
    static boolean panelVisible;
    static boolean panelHidden = true;
    static boolean panelScreenBound; // auto-close when screen closes
    static int panelX = 100, panelY = 100;
    static boolean networkAvailable;
    static String networkName = "";
    static int totalSlotCount;
    static int viewType;
    static int sortMode;
    static boolean sortAsc = true;
    static int gridSize = 3;
    static int visibleRows = 5;
    static int scrollRow;
    static int tickCounter;
    static int hoveredSideButton = -1;
    static int hoveredSlotIndex = -1;
    static int lastMouseX, lastMouseY;
    static KeyMapping KEY_TOGGLE_PANEL;
    private static boolean prefsLoaded;

    // ── Core data ────────────────────────────────────────────────
    static final List<PanelStack> panels = new ArrayList<>();
    static final Map<UUID, Integer> idToIndex = new HashMap<>();
    static final List<PanelStack> displayList = new ArrayList<>();
    static volatile boolean displayDirty = true;
    static final Map<UUID, PendingExtraction> pendingExtractions = new HashMap<>();
    static final PanelDataModel dataModel = new PanelDataModel();

    // ── Mouse/drag state ─────────────────────────────────────────
    static boolean movingPanel;
    static int moveStartMouseX, moveStartMouseY;
    static int moveStartPanelX, moveStartPanelY;
    static boolean scrolling;
    static boolean gridDragging;
    static boolean isShifting;
    static final Set<UUID> gridDragKeys = new LinkedHashSet<>();
    static boolean gridDragCrossedSlots;
    static int clickLockTicks;
    static volatile Set<ResourceLocation> lockedItems = Set.of();

    // ── Tooltip bleed guard ──────────────────────────────────────
    // Set while the side panel is rendering its own tooltips, so
    // the RenderTooltipEvent.Pre interceptor knows to let them
    // through instead of cancelling them as foreign.
    public static volatile boolean isRenderingOurTooltip;

    // ── Animation / delta ────────────────────────────────────────
    static final Map<UUID, SlotAnim> slotAnims = new HashMap<>();
    static final Set<UUID> deltaBatch = new HashSet<>();
    static boolean deltaBatchDirty;

    // ── Inner types ──────────────────────────────────────────────

    static class SlotAnim implements SidePanelRenderer.SlotAnimProvider {
        long startTime;
        int delta;
        SlotAnim(long t, int d) { startTime = t; delta = d; }
        public int deltaValue() { return delta; }
        public boolean expired() { return System.currentTimeMillis() - startTime > 400; }
        public float fade() { return 1.0F - Mth.clamp((System.currentTimeMillis() - startTime) / 400F, 0F, 1F); }
    }

    static class PendingExtraction {
        final ItemStack previousStack;
        final long timestamp;
        final boolean craftable;
        final long createdAt;
        PendingExtraction(ItemStack stack, long ts, boolean cf) {
            this.previousStack = stack.copy();
            this.timestamp = ts;
            this.craftable = cf;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private RSSidePanelClient() {}

    // ── Init ─────────────────────────────────────────────────────

    /**
     * Must be called during mod construction so the key-mapping listener is
     * added before {@code RegisterKeyMappingsEvent} fires.
     */
    private static volatile boolean keyMappingsRegistered;

    public static void registerKeyMappings() {
        if (keyMappingsRegistered) return;
        keyMappingsRegistered = true;
        KEY_TOGGLE_PANEL = new KeyMapping(
                "key.rsi.side_panel",
                KeyConflictContext.UNIVERSAL,
                InputConstants.Type.KEYSYM,
                RSIntegrationConfig.RS_SIDE_PANEL_KEY.get(),
                "key.categories.rsi"
        );

        com.huanghuang.rsintegration.RSIntegrationMod.MOD_BUS.addListener(
                (RegisterKeyMappingsEvent e) -> e.register(KEY_TOGGLE_PANEL));
    }

    public static void init() {
        panelX = RSIntegrationConfig.RS_SIDE_PANEL_X.get();
        panelY = RSIntegrationConfig.RS_SIDE_PANEL_Y.get();
        panelHidden = RSIntegrationConfig.RS_SIDE_PANEL_HIDDEN.get();

        registerKeyMappings();

        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener(RSSidePanelClient::onClientTick);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onRenderGuiPost);
        bus.addListener(EventPriority.LOWEST, RSSidePanelClient::onScreenRenderPost);
        bus.addListener(EventPriority.HIGH, RSSidePanelClient::onKeyInput);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenMousePressed);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenMouseReleased);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenMouseDragged);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenMouseScrolled);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onInputMouseScrolled);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenKeyPressed);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenCharTyped);

        // Cancel foreign tooltips that land inside the side panel area
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onRenderTooltipPre);

        RSIntegrationMod.LOGGER.info("[RSI] SidePanel client initialized.");
    }

    // ── Save ─────────────────────────────────────────────────────

    static void savePosition() {
        RSIntegrationConfig.RS_SIDE_PANEL_X.set(panelX);
        RSIntegrationConfig.RS_SIDE_PANEL_Y.set(panelY);
        RSIntegrationConfig.RS_SIDE_PANEL_HIDDEN.set(panelHidden);
    }

    // ── Item key helper ──────────────────────────────────────────

    public static boolean isItemLocked(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return rl != null && lockedItems.contains(rl);
    }

    /** Called from GridScreenMouseMixin (cross-package) for optimistic client-side toggle. */
    public static void toggleClientLock(ResourceLocation rl) {
        Set<ResourceLocation> cur = new LinkedHashSet<>(lockedItems);
        if (!cur.remove(rl)) cur.add(rl);
        lockedItems = Set.copyOf(cur);
        displayDirty = true;
        clickLockTicks = 5;
    }

    public static String keyOf(ItemStack stack) {
        if (stack == null || stack.getItem() == net.minecraft.world.item.Items.AIR) return "";
        var rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String key = rl != null ? rl.toString() : "";
        String nbt = PanelStack.stableNbtString(stack.getTag());
        if (!nbt.isEmpty()) key += "|" + nbt;
        return key;
    }

    static void clearPendingBySearchKey(String searchKey) {
        var it = pendingExtractions.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (keyOf(e.getValue().previousStack).equals(searchKey)) it.remove();
        }
    }

    // ── Sort guard ───────────────────────────────────────────────

    static boolean canSort() { return !gridDragging; }

    // ── Layout helpers ───────────────────────────────────────────

    static int panelWidth() { return GRID_W; }
    static int panelHeight() { return HEADER_H + visibleRows * SLOT_SIZE + BOTTOM_H; }
    static boolean needsScrollbar() { return getTotalRows() > visibleRows; }

    // ── Containment (delegates to SidePanelInputHandler) ─────────

    static boolean anyPanelContains(double mx, double my) {
        return SidePanelInputHandler.anyPanelContains(mx, my, panelX, panelY, visibleRows, panelHidden);
    }
    static boolean sideButtonContains(double mx, double my) {
        return SidePanelInputHandler.sideButtonContains(mx, my, panelX, panelY);
    }
    static boolean searchBarContains(double mx, double my) {
        return SidePanelInputHandler.searchBarContains(mx, my, panelX, panelY);
    }
    static boolean headerContains(double mx, double my) {
        return SidePanelInputHandler.headerContains(mx, my, panelX, panelY);
    }

    // ── Grid size ────────────────────────────────────────────────

    static int gridSizeToRows(int screenH) {
        return switch (gridSize) {
            case 0 -> {
                int spaceRows = Math.max(2, (screenH - panelY - HEADER_H - BOTTOM_H) / SLOT_SIZE);
                yield Math.min(spaceRows, Math.max(2, getTotalRows()));
            }
            case 1 -> 3;
            case 2 -> 5;
            default -> 8;
        };
    }

    // ═════════════════════════════════════════════════════════════
    //  RENDERING
    // ═════════════════════════════════════════════════════════════

    /** Cancel foreign tooltips that land on the side panel or Machine Hub area. */
    private static void onRenderTooltipPre(net.minecraftforge.client.event.RenderTooltipEvent.Pre event) {
        // Side panel guard
        if (panelVisible && !panelHidden && !isRenderingOurTooltip
                && anyPanelContains(event.getX(), event.getY())) {
            event.setCanceled(true);
            return;
        }
        // Machine Hub guard
        if (com.huanghuang.rsintegration.machine.MachineHub.isVisible()
                && !com.huanghuang.rsintegration.machine.MachineHub.isRenderingOurTooltip
                && com.huanghuang.rsintegration.machine.MachineHub.isWithinBounds(
                        event.getX(), event.getY())) {
            event.setCanceled(true);
        }
    }

    @SuppressWarnings("resource")
    private static void doRenderContext(GuiGraphics g, Minecraft mc) {
        int guiScale = (int) mc.getWindow().getGuiScale();
        lastMouseX = (int) (mc.mouseHandler.xpos() / guiScale);
        lastMouseY = (int) (mc.mouseHandler.ypos() / guiScale);

        visibleRows = gridSizeToRows(mc.getWindow().getGuiScaledHeight());
        scrollRow = SidePanelInputHandler.clampScroll(scrollRow, getTotalRows(), visibleRows);

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        panelX = Mth.clamp(panelX, 0, sw - panelWidth());
        panelY = Mth.clamp(panelY, 0, sh - panelHeight());

        SearchController.ensureWidget(mc);

        var pose = g.pose();
        pose.pushPose();
        pose.translate(0, 0, 400.0F);

        if (panelHidden) {
            SidePanelRenderer.renderCollapsedBar(g, panelX, panelY,
                    networkAvailable, networkName, totalSlotCount);
            hoveredSideButton = -1;
            hoveredSlotIndex = -1;
        } else {
            DisplayListManager.ensureDisplayReady();
            var result = SidePanelRenderer.renderPanel(g, panelX, panelY,
                    visibleRows, scrollRow,
                    networkAvailable, networkName, totalSlotCount,
                    SearchController.searchWidget, SearchController.searchText, SearchController.searchFocused,
                    viewType, sortAsc, sortMode, SearchController.searchMode, gridSize,
                    lastMouseX, lastMouseY,
                    displayList, slotAnims,
                    gridDragging, gridDragKeys);
            hoveredSideButton = result.hoveredSideButton;
            hoveredSlotIndex = result.hoveredSlotIndex;
        }

        pose.popPose();

        // ── Carried item (z=800) ──────────────────────────────────
        ItemStack carried = mc.player.containerMenu.getCarried();
        if (!carried.isEmpty()) {
            int cx = lastMouseX - 8;
            int cy = lastMouseY - 8;
            pose.pushPose();
            pose.translate(0, 0, 800.0F);
            g.renderItem(carried, cx, cy);
            g.renderItemDecorations(mc.font, carried, cx, cy,
                    carried.getCount() > 1 ? String.valueOf(carried.getCount()) : null);
            pose.popPose();
        }

        // ── Tooltips (z=900 → internal translate(0,0,400) → 1300) ─
        // Rendered outside the panel pose so the tooltip's internal
        // z-offset stacks on a fresh base, keeping background fills and
        // text in agreement.  1300 > panel max 700 and held item 800.
        if (!panelHidden && hoveredSlotIndex >= 0
                && hoveredSlotIndex < displayList.size()) {
            ItemStack hs = displayList.get(hoveredSlotIndex).getStack();
            if (!hs.isEmpty()) {
                pose.pushPose();
                pose.translate(0, 0, 900.0F);
                isRenderingOurTooltip = true;
                SidePanelRenderer.renderItemTooltip(g, mc.font, hs,
                        lastMouseX, lastMouseY);
                isRenderingOurTooltip = false;

                if (isItemLocked(hs)) {
                    int col = hoveredSlotIndex % COLUMNS;
                    int row = hoveredSlotIndex / COLUMNS - scrollRow;
                    if (row >= 0 && row < visibleRows) {
                        int lix = panelX + GRID_ITEM_X + col * SLOT_SIZE + 1 + 1;
                        int liy = panelY + HEADER_H + row * SLOT_SIZE + 1 + 1;
                        if (lastMouseX >= lix && lastMouseX < lix + 7
                                && lastMouseY >= liy && lastMouseY < liy + 7) {
                            isRenderingOurTooltip = true;
                            g.renderTooltip(mc.font,
                                    Component.translatable(
                                            "rsi.side_panel.locked_item"),
                                    lastMouseX, lastMouseY);
                            isRenderingOurTooltip = false;
                        }
                    }
                }

                String hsKey = keyOf(hs);
                if (!hsKey.isEmpty()
                        && com.huanghuang.rsintegration.sidepanel.data.BindingCache.getInstance().hasGui(hsKey)) {
                    int col = hoveredSlotIndex % COLUMNS;
                    int row = hoveredSlotIndex / COLUMNS - scrollRow;
                    if (row >= 0 && row < visibleRows) {
                        int gix = panelX + GRID_ITEM_X + col * SLOT_SIZE + 1 + 10;
                        int giy = panelY + HEADER_H + row * SLOT_SIZE + 1 + 10;
                        if (lastMouseX >= gix && lastMouseX < gix + 8
                                && lastMouseY >= giy && lastMouseY < giy + 8) {
                            isRenderingOurTooltip = true;
                            g.renderTooltip(mc.font,
                                    Component.translatable(
                                            "rsi.side_panel.open_machine"),
                                    lastMouseX, lastMouseY);
                            isRenderingOurTooltip = false;
                        }
                    }
                }
                pose.popPose();
            }
        }
        if (!panelHidden && hoveredSideButton >= 0) {
            pose.pushPose();
            pose.translate(0, 0, 900.0F);
            isRenderingOurTooltip = true;
            SidePanelRenderer.renderSideButtonTooltip(g, mc.font,
                    hoveredSideButton,
                    viewType, sortAsc, sortMode,
                    SearchController.searchMode, gridSize,
                    lastMouseX, lastMouseY);
            isRenderingOurTooltip = false;
            pose.popPose();
        }
    }

    @SuppressWarnings("resource")
    private static void onRenderGuiPost(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !panelVisible) return;
        if (mc.screen != null) return;
        doRenderContext(event.getGuiGraphics(), mc);
    }

    // Screens where the side panel must NOT render — modal overlays that
    // should draw on a clean background rather than on top of the RS grid.
    private static final Set<String> SIDE_PANEL_BLOCKED_SCREENS = Set.of(
            "com.huanghuang.rsintegration.crafting.plan.CraftingPlanScreen"
    );

    @SuppressWarnings("resource")
    private static long lastScreenDiagLogTime;

    private static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !panelVisible) return;
        String screenName = event.getScreen().getClass().getName();

        // Diagnostic: log active screen every 5s so we can tell whether
        // the machine GUI actually opened or the RS screen stayed put.
        long now = System.currentTimeMillis();
        if (now - lastScreenDiagLogTime > 5000) {
            lastScreenDiagLogTime = now;
            RSIntegrationMod.LOGGER.debug("[RSI-SidePanel] Active screen: {}", screenName);
        }

        if (RS_SCREEN_CLASSES.contains(screenName)) return;
        if (SIDE_PANEL_BLOCKED_SCREENS.contains(screenName)) return;
        doRenderContext(event.getGuiGraphics(), mc);
    }

    // ── JEI item lookup ──────────────────────────────────────────

    static void showJeiForHoveredItem(boolean usage) {
        if (hoveredSlotIndex < 0 || hoveredSlotIndex >= displayList.size()) return;
        SidePanelJeiBridge.showJeiForItem(usage, displayList.get(hoveredSlotIndex).getStack());
    }

    // ── Preference persistence ───────────────────────────────────
    private static final java.nio.file.Path PANEL_PREFS_PATH =
            net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("rs_integration").resolve("side_panel.json");

    static void loadPanelPreferences() {
        if (prefsLoaded) return;
        prefsLoaded = true;
        var data = SidePanelPreferences.load(PANEL_PREFS_PATH);
        sortMode   = data.sortMode;
        sortAsc    = data.sortAsc;
        SearchController.searchMode = data.searchMode;
        gridSize   = data.gridSize;
        viewType   = data.viewType;
    }

    static void savePanelPreferences() {
        if (!prefsLoaded) return;
        SidePanelPreferences.save(PANEL_PREFS_PATH,
                new SidePanelPreferences.Data(sortMode, sortAsc, SearchController.searchMode, gridSize, viewType));
    }

    // ── Data helpers ─────────────────────────────────────────────

    static PanelStack getById(UUID id) {
        Integer idx = idToIndex.get(id);
        return idx != null && idx < panels.size() ? panels.get(idx) : null;
    }

    static void removePanel(UUID id) {
        Integer idx = idToIndex.remove(id);
        if (idx == null) return;
        panels.remove((int) idx);
        totalSlotCount = Math.max(0, totalSlotCount - 1);
        for (int i = idx; i < panels.size(); i++)
            idToIndex.put(panels.get(i).getId(), i);
    }

    static void updateScrollFromMouse(double my) {
        scrollRow = SidePanelInputHandler.updateScrollFromMouse(my, panelY,
                visibleRows, getTotalRows(), scrollRow);
    }

    static void handleSideButtonClick(int idx) {
        var result = SidePanelInputHandler.cycleSideButton(idx,
                viewType, sortAsc, sortMode, SearchController.searchMode, gridSize, scrollRow);
        viewType = result.viewType;
        sortAsc = result.sortAsc;
        sortMode = result.sortMode;
        SearchController.searchMode = result.searchMode;
        gridSize = result.gridSize;
        scrollRow = result.scrollRow;
        if (result.needsJeiPull) {
            SearchController.lastJeiFilterText = "";
            SearchController.pullJeiFilter();
        } else if (result.needsJeiClear) {
            SidePanelJeiBridge.clearFilter();
        }
        savePanelPreferences();
        displayDirty = true;
    }

    static void clampScroll() {
        scrollRow = SidePanelInputHandler.clampScroll(scrollRow, getTotalRows(), visibleRows);
    }

    static int getTotalRows() {
        DisplayListManager.ensureDisplayReady();
        return (int) Math.ceil(displayList.size() / (double) COLUMNS);
    }

    static void recordSlotAnim(UUID id, int delta) {
        slotAnims.put(id, new SlotAnim(System.currentTimeMillis(), delta));
    }

    // ═════════════════════════════════════════════════════════════
    //  EVENT HANDLERS — thin wrappers delegating to extracted classes
    // ═════════════════════════════════════════════════════════════

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        SidePanelTickManager.onClientTick(event);
    }

    private static void onKeyInput(InputEvent.Key event) {
        SearchController.onKeyInput(event);
    }

    private static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        SearchController.onScreenKeyPressed(event);
    }

    private static void onScreenCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        SearchController.onScreenCharTyped(event);
    }

    private static void onScreenMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        SidePanelMouseHandler.onScreenMousePressed(event);
    }

    private static void onScreenMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        SidePanelMouseHandler.onScreenMouseReleased(event);
    }

    private static void onScreenMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        SidePanelMouseHandler.onScreenMouseDragged(event);
    }

    private static void onScreenMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        SidePanelMouseHandler.onScreenMouseScrolled(event);
    }

    private static void onInputMouseScrolled(InputEvent.MouseScrollingEvent event) {
        SidePanelMouseHandler.onInputMouseScrolled(event);
    }

    // ── Sync / Delta — delegated to SyncHandler ─────────────────
    // Called by RSSidePanelDeltaPacket.handle() and RSSidePanelSyncPacket.handle()

    public static void onSyncReceived(RSSidePanelSyncPacket packet) {
        SyncHandler.onSyncReceived(packet);
    }

    public static void onDeltaReceived(UUID id, ItemStack stack, long timestamp, boolean craftable) {
        SyncHandler.onDeltaReceived(id, stack, timestamp, craftable);
    }
}
