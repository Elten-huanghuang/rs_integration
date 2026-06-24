package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
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
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.ForgeRegistries;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import org.lwjgl.glfw.GLFW;

import com.github.stuxuhai.jpinyin.PinyinFormat;
import com.github.stuxuhai.jpinyin.PinyinHelper;
import java.util.*;

@OnlyIn(Dist.CLIENT)
public final class RSSidePanelClient {

    // ── RS-native layout (matches GridScreen grid.png UV strips) ──
    private static final int SLOT_SIZE        = 18;
    private static final int COLUMNS          = 9;
    private static final int GRID_W           = 193;
    private static final int HEADER_H         = 19;
    private static final int BOTTOM_H         = 7;
    private static final int GRID_ITEM_X      = 8;
    private static final int SCROLLBAR_X      = 174;
    private static final int SIDE_BTN_SIZE  = 18;
    private static final int SIDE_BTN_GAP   = 2;
    private static final int SIDE_BTN_PITCH = SIDE_BTN_SIZE + SIDE_BTN_GAP;
    private static final int SIDE_BTN_FLOAT_X = -20;

    private static final ResourceLocation RS_GRID_TEX =
            new ResourceLocation("refinedstorage", "textures/gui/grid.png");
    private static final ResourceLocation RS_ICONS_TEX =
            new ResourceLocation(RSIntegrationMod.MOD_ID, "textures/gui/icons.png");

    private static final Set<String> RS_SCREEN_CLASSES = Set.of(
            "com.refinedmods.refinedstorage.screen.grid.GridScreen",
            "com.refinedmods.refinedstorage.screen.CraftingMonitorScreen",
            "com.refinedmods.refinedstorage.screen.ControllerScreen"
    );

    // ── state ───────────────────────────────────────────────────
    static boolean panelVisible;
    static boolean panelHidden = true;
    static int panelX = 100, panelY = 100;

    static boolean networkAvailable;
    static String networkName = "";

    // ---- Unified data model: one Map instead of three parallel Maps.
    //      Atomic put/remove guarantees item/timestamp/craftable stay in sync.
    //      LinkedHashMap preserves insertion order for sort-mode-3 (last-modified).
    static final Map<String, ItemEntry> itemMap = new LinkedHashMap<>();
    static int totalSlotCount;

    // Pending extractions: keys that the client predicted extraction for but
    // haven't been confirmed by a server delta yet.  On full sync or delta
    // confirmation they are cleared.  Stale entries are reverted on tick.
    static final Map<String, PendingExtraction> pendingExtractions = new HashMap<>();

    // displayList: sorted + filtered view, rebuilt lazily when displayDirty=true
    static final List<ItemStack> displayList = new ArrayList<>();
    static volatile boolean displayDirty = true;

    static String searchText = "";
    static boolean searchOpen;
    static int sortMode;
    static boolean sortAsc = true;
    static int searchMode;
    static int gridSize = 3;
    static int viewType;
    static int visibleRows = 5;

    static final List<String> searchHistory = new ArrayList<>();
    static int historyIndex = -1;
    static String lastJeiFilterText = "";
    static int scrollRow;

    static int tickCounter;
    static int clickLockTicks;
    static EditBox searchWidget;
    static boolean searchChangedSinceBlur;
    static boolean searchFocused;

    // Drag / move / scrollbar
    static boolean movingPanel;
    static int moveStartMouseX, moveStartMouseY;
    static int moveStartPanelX, moveStartPanelY;
    static boolean scrolling;
    static boolean gridDragging;
    // Store item keys (String) instead of integer indices for O(1) identity
    static final Set<String> gridDragKeys = new LinkedHashSet<>();
    static boolean gridDragCrossedSlots;

    static int hoveredSideButton = -1;
    static int hoveredSlotIndex = -1;
    static int lastMouseX, lastMouseY;

    static KeyMapping KEY_TOGGLE_PANEL;

    // ── inner types ────────────────────────────────────────────────

    static class ItemEntry {
        final ItemStack stack;
        long timestamp;
        boolean craftable;

        ItemEntry(ItemStack stack, long timestamp, boolean craftable) {
            this.stack = stack;
            this.timestamp = timestamp;
            this.craftable = craftable;
        }
    }

    static class PendingExtraction {
        final ItemStack previousStack; // snapshot before prediction
        final long timestamp;
        final boolean craftable;
        final long createdAt;

        PendingExtraction(ItemStack stack, long timestamp, boolean craftable) {
            this.previousStack = stack.copy();
            this.timestamp = timestamp;
            this.craftable = craftable;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private RSSidePanelClient() {}

    // ── init ────────────────────────────────────────────────────

    public static void init() {
        panelX = RSIntegrationConfig.RS_SIDE_PANEL_X.get();
        panelY = RSIntegrationConfig.RS_SIDE_PANEL_Y.get();
        panelHidden = RSIntegrationConfig.RS_SIDE_PANEL_HIDDEN.get();

        KEY_TOGGLE_PANEL = new KeyMapping(
                "key.rsi.side_panel",
                KeyConflictContext.UNIVERSAL,
                InputConstants.Type.KEYSYM,
                RSIntegrationConfig.RS_SIDE_PANEL_KEY.get(),
                "key.categories.rsi"
        );

        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(KEY_TOGGLE_PANEL));

        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener(RSSidePanelClient::onClientTick);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onRenderGuiPost);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenRenderPost);
        bus.addListener(EventPriority.HIGH, RSSidePanelClient::onKeyInput);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenMousePressed);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenMouseReleased);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenMouseDragged);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenMouseScrolled);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onInputMouseScrolled);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenKeyPressed);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenCharTyped);

        RSIntegrationMod.LOGGER.info("[RSI] SidePanel client initialized.");
    }

    // ── save ────────────────────────────────────────────────────

    static void savePosition() {
        RSIntegrationConfig.RS_SIDE_PANEL_X.set(panelX);
        RSIntegrationConfig.RS_SIDE_PANEL_Y.set(panelY);
        RSIntegrationConfig.RS_SIDE_PANEL_HIDDEN.set(panelHidden);
    }

    // ── item key helper ─────────────────────────────────────────

    private static String keyOf(ItemStack stack) {
        if (stack == null || stack.getItem() == net.minecraft.world.item.Items.AIR) return "";
        var rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String key = rl != null ? rl.toString() : "";
        // hasTag() checks !isEmpty() internally, so it returns false when
        // count==0 even if the tag is non-null.  Bypass via getTag() so
        // zero-count delta packets still carry NBT and match the correct slot.
        if (stack.getTag() != null && !stack.getTag().isEmpty()) {
            key += "|" + stack.getTag().toString();
        }
        return key;
    }

    // ── sync (full snapshot) ────────────────────────────────────

    static void onSyncReceived(RSSidePanelSyncPacket packet) {
        clickLockTicks = 0;
        itemMap.clear();
        pendingExtractions.clear(); // full sync overrides all predictions
        List<ItemStack> items = packet.items;
        List<Long> timestamps = packet.timestamps;
        List<Boolean> flags = packet.craftableFlags;
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty()) continue;
            String k = keyOf(s);
            if (k.isEmpty()) continue;
            itemMap.put(k, new ItemEntry(s,
                    i < timestamps.size() ? timestamps.get(i) : 0L,
                    i < flags.size() ? flags.get(i) : false));
        }
        totalSlotCount = packet.totalSlotCount;
        networkAvailable = packet.networkAvailable;
        networkName = packet.networkName;
        displayDirty = true;
        clampScroll();
    }

    // ── incremental delta ───────────────────────────────────────

    static void onDeltaReceived(ItemStack stack, long timestamp, boolean craftable) {
        if (stack == null || stack.getItem() == null) return;
        String k = keyOf(stack);
        if (k.isEmpty()) return;

        // Server delta is authoritative — clear any pending prediction for this key
        pendingExtractions.remove(k);

        int count = stack.getCount();
        if (count <= 0) {
            itemMap.remove(k);
        } else {
            ItemEntry existing = itemMap.get(k);
            if (existing != null) {
                existing.stack.setCount(count);
                existing.timestamp = timestamp;
                existing.craftable = craftable;
            } else {
                itemMap.put(k, new ItemEntry(stack.copy(), timestamp, craftable));
            }
        }
        displayDirty = true;
    }

    // ── sort guard (RS native: prevent re-sort during shift operations) ──

    private static boolean canSort() {
        if (gridDragging) return false;
        return true;
    }

    // ── layout helpers ──────────────────────────────────────────

    private static int panelWidth() { return GRID_W; }

    private static int panelHeight() {
        return HEADER_H + visibleRows * SLOT_SIZE + BOTTOM_H;
    }

    private static boolean needsScrollbar() {
        return getTotalRows() > visibleRows;
    }

    // ── containment ─────────────────────────────────────────────

    private static boolean anyPanelContains(double mx, double my) {
        int pw = panelWidth(), ph = panelHeight();
        if (mx >= panelX - 2 && mx < panelX + pw + 2
                && my >= panelY - 2 && my < panelY + ph + 2)
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

    private static boolean sideButtonContains(double mx, double my) {
        int bx = panelX + SIDE_BTN_FLOAT_X, by = panelY + HEADER_H + 1;
        return mx >= bx && mx < bx + SIDE_BTN_SIZE
                && my >= by && my < by + 5 * SIDE_BTN_PITCH;
    }

    private static boolean searchBarContains(double mx, double my) {
        int sx = panelX + 81, sy = panelY + 7;
        return mx >= sx && mx < sx + 82 && my >= sy && my < sy + 9;
    }

    private static boolean headerContains(double mx, double my) {
        return mx >= panelX && mx < panelX + panelWidth()
                && my >= panelY && my < panelY + HEADER_H;
    }

    // ── key input ──────────────────────────────────────────────

    @SuppressWarnings("resource")
    private static void onKeyInput(InputEvent.Key event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (KEY_TOGGLE_PANEL.isActiveAndMatches(
                InputConstants.getKey(event.getKey(), event.getScanCode()))
                && event.getAction() == GLFW.GLFW_PRESS) {
            panelVisible = !panelVisible;
            if (panelVisible && !panelHidden) {
                RSSidePanelNetworkHandler.sendRequestSync();
                scrollRow = 0;
            }
            return;
        }
        if (!panelVisible || panelHidden) return;
        // JEI recipe/uses keys when hovering over a panel item
        if (event.getAction() == GLFW.GLFW_PRESS) {
            int key = event.getKey();
            if ((key == GLFW.GLFW_KEY_U || key == GLFW.GLFW_KEY_R) && mc.screen == null) {
                showJeiForHoveredItem(key == GLFW.GLFW_KEY_U);
                return;
            }
        }
        if (!searchFocused) return;
        if (Minecraft.getInstance().screen != null) return;
        handleTextInput(event.getKey(), event.getScanCode(), event.getAction(), false);
    }

    @SuppressWarnings("resource")
    private static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!panelVisible || panelHidden) return;
        if (searchFocused) {
            event.setCanceled(true);
            handleTextInput(event.getKeyCode(), event.getScanCode(), GLFW.GLFW_PRESS, true);
            return;
        }
        if (anyPanelContains(lastMouseX, lastMouseY)) {
            // JEI recipe (R) / uses (U) for hovered panel item
            int key = event.getKeyCode();
            if (key == GLFW.GLFW_KEY_U || key == GLFW.GLFW_KEY_R) {
                showJeiForHoveredItem(key == GLFW.GLFW_KEY_U);
                event.setCanceled(true);
                return;
            }
            boolean isModifier = event.getKeyCode() == GLFW.GLFW_KEY_LEFT_SHIFT
                    || event.getKeyCode() == GLFW.GLFW_KEY_RIGHT_SHIFT
                    || event.getKeyCode() == GLFW.GLFW_KEY_LEFT_CONTROL
                    || event.getKeyCode() == GLFW.GLFW_KEY_RIGHT_CONTROL
                    || event.getKeyCode() == GLFW.GLFW_KEY_LEFT_ALT
                    || event.getKeyCode() == GLFW.GLFW_KEY_RIGHT_ALT;
            if (isModifier)
                event.setCanceled(true);
        }
    }

    @SuppressWarnings("resource")
    private static void onScreenCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (!panelVisible || panelHidden || !searchFocused) return;
        if (searchWidget == null) return;
        char cp = event.getCodePoint();
        if (cp >= 32 && cp != 127 && searchText.length() < 64) {
            searchWidget.charTyped(cp, Screen.hasControlDown() ? GLFW.GLFW_MOD_CONTROL : 0);
            pushJeiFilter();
            event.setCanceled(true);
        }
    }

    private static void handleTextInput(int key, int scanCode, int action, boolean screenOpen) {
        if (action != GLFW.GLFW_PRESS) return;
        if (searchWidget == null) return;

        if (key == GLFW.GLFW_KEY_ESCAPE) { onSearchBlur(); return; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if ((searchMode % 2) == 1) {
                ensureDisplayList();
                if (!displayList.isEmpty()) {
                    RSSidePanelNetworkHandler.sendClick(displayList.get(0),
                            RSSidePanelClickPacket.ACTION_EXTRACT_ONE, false);
                }
            }
            onSearchBlur();
            return;
        }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            if (searchHistory.isEmpty()) return;
            searchWidget.setFocused(true);
            if (key == GLFW.GLFW_KEY_UP) {
                if (historyIndex < searchHistory.size() - 1) {
                    historyIndex++;
                    searchWidget.setValue(searchHistory.get(searchHistory.size() - 1 - historyIndex));
                    searchWidget.moveCursorToEnd();
                }
            } else {
                if (historyIndex > 0) {
                    historyIndex--;
                    searchWidget.setValue(searchHistory.get(searchHistory.size() - 1 - historyIndex));
                    searchWidget.moveCursorToEnd();
                } else {
                    historyIndex = -1;
                    searchWidget.setValue("");
                }
            }
            pushJeiFilter();
            return;
        }
        historyIndex = -1;
        if (searchWidget.keyPressed(key, scanCode,
                Screen.hasControlDown() ? GLFW.GLFW_MOD_CONTROL : 0)) {
            pushJeiFilter();
            return;
        }
        if (!screenOpen) {
            String ch = glfwKeyToChar(key, scanCode);
            if (ch != null) {
                for (int i = 0; i < ch.length(); i++)
                    searchWidget.charTyped(ch.charAt(i),
                            Screen.hasControlDown() ? GLFW.GLFW_MOD_CONTROL : 0);
                pushJeiFilter();
            }
        }
    }

    private static String glfwKeyToChar(int key, int scanCode) {
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

    private static void pushSearchHistory() {
        String text = searchWidget != null ? searchWidget.getValue() : searchText;
        if (text.isEmpty()) return;
        searchHistory.remove(text);
        searchHistory.add(text);
        if (searchHistory.size() > 50) searchHistory.remove(0);
        historyIndex = -1;
    }

    private static void onSearchBlur() {
        if (searchWidget != null) searchText = searchWidget.getValue();
        if (searchChangedSinceBlur && !searchText.isEmpty()) pushSearchHistory();
        searchFocused = false;
    }

    private static void pushJeiFilter() {
        if (searchMode < 2) return;
        try {
            IJeiRuntime rt = com.huanghuang.rsintegration.integration.RSJeiPlugin.getRuntime();
            if (rt != null) rt.getIngredientFilter().setFilterText(searchText);
        } catch (Exception ignored) {}
    }

    private static void pullJeiFilter() {
        if (searchMode < 2) return;
        try {
            IJeiRuntime rt = com.huanghuang.rsintegration.integration.RSJeiPlugin.getRuntime();
            if (rt != null) {
                String t = rt.getIngredientFilter().getFilterText();
                if (t != null && !t.equals(lastJeiFilterText)) {
                    lastJeiFilterText = t;
                    if (!t.equals(searchText)) {
                        searchText = t;
                        if (searchWidget != null) searchWidget.setValue(t);
                        historyIndex = -1;
                        displayDirty = true;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /** Show JEI recipes (R) or uses (U) for the currently hovered item. */
    private static void showJeiForHoveredItem(boolean usage) {
        if (hoveredSlotIndex < 0 || hoveredSlotIndex >= displayList.size()) return;
        ItemStack stack = displayList.get(hoveredSlotIndex);
        if (stack.isEmpty()) return;
        try {
            IJeiRuntime runtime = com.huanghuang.rsintegration.integration.RSJeiPlugin.getRuntime();
            if (runtime == null) return;
            IFocusFactory ff = runtime.getJeiHelpers().getFocusFactory();
            var focus = ff.createFocus(
                    usage ? RecipeIngredientRole.INPUT : RecipeIngredientRole.OUTPUT,
                    VanillaTypes.ITEM_STACK, stack);
            runtime.getRecipesGui().show(focus);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-SidePanel] JEI {} failed: {}",
                    usage ? "showUses" : "showRecipes", e.toString());
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  RENDERING
    // ═════════════════════════════════════════════════════════════

    // ── Common render entry point ────────────────────────────────

    @SuppressWarnings("resource")
    private static void doRenderContext(GuiGraphics g, Minecraft mc) {
        int guiScale = (int) mc.getWindow().getGuiScale();
        lastMouseX = (int) (mc.mouseHandler.xpos() / guiScale);
        lastMouseY = (int) (mc.mouseHandler.ypos() / guiScale);

        visibleRows = gridSizeToRows(mc.getWindow().getGuiScaledHeight());
        scrollRow = Mth.clamp(scrollRow, 0, Math.max(0, getTotalRows() - visibleRows));

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        panelX = Mth.clamp(panelX, 0, sw - panelWidth());
        panelY = Mth.clamp(panelY, 0, sh - panelHeight());

        var pose = g.pose();
        pose.pushPose();
        pose.translate(0, 0, 150.0F);

        if (panelHidden)
            drawCollapsedBar(g);
        else
            drawPanel(g, mc);

        pose.popPose();
    }

    // ── HUD render (no screen open) ──────────────────────────────

    @SuppressWarnings("resource")
    private static void onRenderGuiPost(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !panelVisible) return;
        if (mc.screen != null) return;

        doRenderContext(event.getGuiGraphics(), mc);
    }

    // ── Screen render (e.g. chest, furnace — after ContainerScreen) ──

    @SuppressWarnings("resource")
    private static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !panelVisible) return;
        if (RS_SCREEN_CLASSES.contains(event.getScreen().getClass().getName())) return;

        doRenderContext(event.getGuiGraphics(), mc);
    }

    private static int gridSizeToRows(int screenH) {
        return switch (gridSize) {
            case 0 -> {
                int spaceRows = Math.max(2, (screenH - panelY - HEADER_H - BOTTOM_H) / SLOT_SIZE);
                int contentRows = getTotalRows();
                yield Math.min(spaceRows, Math.max(2, contentRows));
            }
            case 1 -> 3;
            case 2 -> 5;
            default -> 8;
        };
    }

    @SuppressWarnings("resource")
    private static void drawPanel(GuiGraphics g, Minecraft mc) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();

        var font = mc.font;
        int gy = panelY + HEADER_H;
        int gridH = visibleRows * SLOT_SIZE;

        // ── 1. Header strip ──────────────────────────────────────
        g.blit(RS_GRID_TEX, panelX, panelY, 0, 0, GRID_W, HEADER_H);

        // ── 2. Row backgrounds ───────────────────────────────────
        for (int row = 0; row < visibleRows; row++) {
            int v = row == 0 ? 19 : (row == visibleRows - 1 ? 55 : 37);
            int ry = gy + row * SLOT_SIZE;
            g.blit(RS_GRID_TEX, panelX, ry, 0, v, GRID_W, SLOT_SIZE);
        }

        // ── 3. Bottom strip ──────────────────────────────────────
        int by = gy + gridH;
        g.blit(RS_GRID_TEX, panelX, by, 0, 73, GRID_W, BOTTOM_H);

        // ── 4. Header content ────────────────────────────────────
        String title;
        int titleColor;
        if (networkAvailable) {
            title = !networkName.isEmpty() ? networkName : "Refined Storage";
            titleColor = 0xFF7BAAF7;
        } else {
            title = Component.translatable("rsi.side_panel.no_network").getString();
            titleColor = 0xFFFF5555;
        }
        int titleMaxW = 73;
        g.drawString(font, font.plainSubstrByWidth(title, titleMaxW),
                panelX + GRID_ITEM_X, panelY + 7, titleColor);

        // Search box
        int sx = panelX + 81, sy = panelY + 7, sw = 82;
        if (searchWidget == null) {
            searchWidget = new EditBox(font, sx, sy, sw, 9,
                    Component.translatable("rsi.side_panel.search_hint"));
            searchWidget.setBordered(false);
            searchWidget.setTextColor(0xFFFFFFFF);
            searchWidget.setTextColorUneditable(0xFF777777);
            searchWidget.setValue(searchText);
            searchWidget.setResponder(t -> {
                searchText = t;
                searchChangedSinceBlur = true;
                displayDirty = true;
            });
        }
        searchWidget.setX(sx);
        searchWidget.setY(sy);
        searchWidget.setWidth(sw);
        searchWidget.setFocused(searchFocused);
        searchWidget.render(g, lastMouseX, lastMouseY, 0);

        // ── 5. Side buttons ──────────────────────────────────────
        hoveredSideButton = -1;
        int btnX = panelX + SIDE_BTN_FLOAT_X;
        int sby = panelY + HEADER_H + 1;
        for (int i = 0; i < 5; i++) {
            drawSideButton(g, btnX, sby + i * SIDE_BTN_PITCH, i);
        }

        // ── 6. Fold button ───────────────────────────────────────
        int foldX = panelX + GRID_W - 16;
        int foldY = panelY + 2;
        boolean foldHovered = lastMouseX >= foldX && lastMouseX < foldX + 14
                && lastMouseY >= foldY && lastMouseY < foldY + 14;
        g.blit(RS_ICONS_TEX, foldX, foldY, foldHovered ? 16 : 0, 128, 14, 14, 256, 256);

        // ── 7. Grid items (SCISSOR CLIPPED) ──────────────────────
        int itemLeft = panelX + GRID_ITEM_X;
        int itemTop  = gy;
        int itemRight = itemLeft + COLUMNS * SLOT_SIZE;
        int itemBottom = itemTop + gridH;
        g.enableScissor(itemLeft, itemTop, itemRight, itemBottom);

        hoveredSlotIndex = -1;

        for (int row = 0; row < visibleRows; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                int dIdx = (scrollRow + row) * COLUMNS + col;
                if (dIdx >= displayList.size()) break;
                ItemStack stack = displayList.get(dIdx);
                if (stack.isEmpty()) continue;

                int ix = itemLeft + col * SLOT_SIZE;
                int iy = itemTop + row * SLOT_SIZE;

                boolean hovered = lastMouseX >= ix && lastMouseX < ix + SLOT_SIZE
                        && lastMouseY >= iy && lastMouseY < iy + SLOT_SIZE;
                boolean dragHighlight = gridDragging
                        && gridDragKeys.contains(keyOf(stack));

                if (dragHighlight) {
                    g.fill(ix + 1, iy + 1, ix + 17, iy + 17, 0x806699CC);
                } else if (hovered) {
                    g.fill(ix + 1, iy + 1, ix + 17, iy + 17, 0x80FFFFFF);
                }

                g.renderItem(stack, ix + 1, iy + 1);

                if (stack.getCount() > 1) {
                    String cnt = formatCount(stack.getCount());
                    var p = g.pose();
                    p.pushPose();
                    p.translate(0, 0, 200);
                    int tx = ix + 17 - font.width(cnt);
                    int ty = iy + 10;
                    g.drawString(font, cnt, tx, ty, 0xFFFFFF);
                    p.popPose();
                } else {
                    g.renderItemDecorations(font, stack, ix + 1, iy + 1, "");
                }

                if (hovered) hoveredSlotIndex = dIdx;
            }
        }

        g.disableScissor();

        // ── 8. Scrollbar ─────────────────────────────────────────
        drawScrollbar(g);

        // ── 9. Tooltips ──────────────────────────────────────────

        if (hoveredSlotIndex >= 0 && hoveredSlotIndex < displayList.size()) {
            ItemStack hs = displayList.get(hoveredSlotIndex);
            if (!hs.isEmpty())
                renderItemTooltip(g, font, hs, lastMouseX, lastMouseY);
        }
        if (hoveredSideButton >= 0)
            renderSideButtonTooltip(g, font, hoveredSideButton);
    }

    // ── drawSideButton ──────────────────────────────────────────

    private static void drawSideButton(GuiGraphics g, int bx, int by, int idx) {
        boolean hovered = lastMouseX >= bx && lastMouseX < bx + SIDE_BTN_SIZE
                && lastMouseY >= by && lastMouseY < by + SIDE_BTN_SIZE;
        if (hovered) hoveredSideButton = idx;

        var p = g.pose();
        p.pushPose();
        p.translate(0, 0, 20);

        int bgV = hovered ? 54 : 16;
        g.blit(RS_ICONS_TEX, bx, by, 238, bgV, SIDE_BTN_SIZE, SIDE_BTN_SIZE, 256, 256);

        int u = 0, v = 0;
        switch (idx) {
            case 0: u = viewType * 16; v = 112; break;
            case 1: u = sortAsc ? 0 : 16; v = 16; break;
            case 2:
                switch (sortMode) {
                    case 0 -> { u = 16; v = 32; }
                    case 1 -> { u = 0; v = 32; }
                    case 2 -> { u = 32; v = 32; }
                    default -> { u = 48; v = 48; }
                }
                break;
            case 3: u = (searchMode % 2 == 1) ? 16 : 0; v = 96; break;
            case 4:
                u = switch (gridSize) { case 0 -> 112; case 1 -> 64; case 2 -> 80; default -> 96; };
                v = 64;
                break;
            default: { p.popPose(); return; }
        }
        g.blit(RS_ICONS_TEX, bx + 1, by + 1, u, v, 16, 16, 256, 256);
        p.popPose();
    }

    // ── drawScrollbar ───────────────────────────────────────────

    private static void drawScrollbar(GuiGraphics g) {
        int totalRows = getTotalRows();
        if (totalRows <= visibleRows) return;
        int maxScroll = Math.max(0, totalRows - visibleRows);
        if (maxScroll <= 0) return;

        int sx = panelX + SCROLLBAR_X;
        int sy = panelY + HEADER_H + 2;
        int trackH = visibleRows * SLOT_SIZE - 4;
        int thumbH = 15;
        int trackAvail = Math.max(1, trackH - thumbH);
        int thumbY = sy + (int) Math.round((double) trackAvail * scrollRow / maxScroll);

        g.blit(RS_ICONS_TEX, sx, thumbY, 232, 0, 12, 15, 256, 256);
    }

    // ── drawCollapsedBar ────────────────────────────────────────

    @SuppressWarnings("resource")
    private static void drawCollapsedBar(GuiGraphics g) {
        var font = Minecraft.getInstance().font;
        // Full-width header strip — a long bar, not a tiny stub
        g.blit(RS_GRID_TEX, panelX, panelY, 0, 0, GRID_W, HEADER_H);

        String title;
        int titleColor;
        if (networkAvailable) {
            title = !networkName.isEmpty() ? networkName : "Refined Storage";
            titleColor = 0xFF7BAAF7;
        } else {
            title = Component.translatable("rsi.side_panel.no_network").getString();
            titleColor = 0xFFFF5555;
        }
        int titleMaxW = 73;
        g.drawString(font, font.plainSubstrByWidth(title, titleMaxW),
                panelX + GRID_ITEM_X, panelY + 7, titleColor);
        // Expand arrow
        g.drawString(font, "▶", panelX + GRID_W - 16, panelY + 5, 0xFFAAAAAA);
    }

    // ── tooltips ────────────────────────────────────────────────

    @SuppressWarnings("resource")
    private static void renderItemTooltip(GuiGraphics g, net.minecraft.client.gui.Font font,
                                          ItemStack stack, int mx, int my) {
        List<Component> lines = new ArrayList<>(stack.getTooltipLines(
                Minecraft.getInstance().player,
                Minecraft.getInstance().options.advancedItemTooltips
                        ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                        : net.minecraft.world.item.TooltipFlag.Default.NORMAL));
        int stored = stack.getCount();
        if (stored > 0)
            lines.add(Component.literal("§7" +
                    Component.translatable("rsi.side_panel.total", formatCount(stored)).getString()));
        g.renderComponentTooltip(font, lines, mx, my);
    }

    private static void renderSideButtonTooltip(GuiGraphics g, net.minecraft.client.gui.Font font, int idx) {
        String label, mode;
        switch (idx) {
            case 0:
                label = Component.translatable("rsi.side_panel.btn.view_type").getString();
                String vk = switch (viewType) {
                    case 1 -> "rsi.side_panel.btn.view_type.non_craftables";
                    case 2 -> "rsi.side_panel.btn.view_type.craftables";
                    default -> "rsi.side_panel.btn.view_type.all";
                };
                mode = Component.translatable(vk).getString();
                break;
            case 1:
                label = Component.translatable("rsi.side_panel.btn.sort_dir").getString();
                mode = Component.translatable(sortAsc ? "rsi.side_panel.btn.sort_dir.asc" : "rsi.side_panel.btn.sort_dir.desc").getString();
                break;
            case 2:
                label = Component.translatable("rsi.side_panel.btn.sort_type").getString();
                String mk = switch (sortMode) {
                    case 0 -> "rsi.side_panel.btn.sort_type.name";
                    case 1 -> "rsi.side_panel.btn.sort_type.count";
                    case 2 -> "rsi.side_panel.btn.sort_type.id";
                    default -> "rsi.side_panel.btn.sort_type.last_modified";
                };
                mode = Component.translatable(mk).getString();
                break;
            case 3:
                label = Component.translatable("rsi.side_panel.btn.search_mode").getString();
                String sk = switch (searchMode) {
                    case 0 -> "rsi.side_panel.btn.search_mode.normal";
                    case 1 -> "rsi.side_panel.btn.search_mode.normal_auto";
                    case 2 -> "rsi.side_panel.btn.search_mode.jei";
                    case 3 -> "rsi.side_panel.btn.search_mode.jei_auto";
                    case 4 -> "rsi.side_panel.btn.search_mode.jei_2way";
                    default -> "rsi.side_panel.btn.search_mode.jei_2way_auto";
                };
                mode = Component.translatable(sk).getString();
                List<Component> extra = new ArrayList<>();
                extra.add(Component.literal(label));
                extra.add(Component.literal("§7" + mode));
                extra.add(Component.literal(""));
                extra.add(Component.literal("§b" + Component.translatable("rsi.side_panel.search.prefix_mod").getString()));
                extra.add(Component.literal("§b" + Component.translatable("rsi.side_panel.search.prefix_tooltip").getString()));
                g.renderComponentTooltip(font, extra, lastMouseX, lastMouseY);
                return;
            case 4:
                label = Component.translatable("rsi.side_panel.btn.grid_size").getString();
                String gk = switch (gridSize) {
                    case 0 -> "rsi.side_panel.btn.grid_size.stretch";
                    case 1 -> "rsi.side_panel.btn.grid_size.small";
                    case 2 -> "rsi.side_panel.btn.grid_size.medium";
                    default -> "rsi.side_panel.btn.grid_size.large";
                };
                mode = Component.translatable(gk).getString();
                break;
            default: return;
        }
        g.renderComponentTooltip(font, List.of(Component.literal(label), Component.literal("§7" + mode)), lastMouseX, lastMouseY);
    }

    // ════════════════════════════════════════════════════════════
    //  MOUSE HANDLERS
    // ════════════════════════════════════════════════════════════

    @SuppressWarnings("resource")
    private static void onScreenMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        var mc = Minecraft.getInstance();
        if (!panelVisible || mc.player == null) return;
        if (mc.screen != null && RS_SCREEN_CLASSES.contains(mc.screen.getClass().getName())) return;

        double mx = event.getMouseX(), my = event.getMouseY();
        int btn = event.getButton();

        if (panelHidden) {
            if (mx >= panelX && mx < panelX + GRID_W && my >= panelY && my < panelY + HEADER_H) {
                if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    movingPanel = true;
                    moveStartMouseX = (int) mx;
                    moveStartMouseY = (int) my;
                    moveStartPanelX = panelX;
                    moveStartPanelY = panelY;
                } else if (btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    panelHidden = false;
                    RSIntegrationConfig.RS_SIDE_PANEL_HIDDEN.set(false);
                    searchText = "";
                    if (searchWidget != null) searchWidget.setValue("");
                    searchFocused = false;
                    scrollRow = 0;
                    RSSidePanelNetworkHandler.sendRequestSync();
                }
                event.setCanceled(true);
            }
            return;
        }

        if (!anyPanelContains(mx, my)) return;
        event.setCanceled(true);

        if (searchBarContains(mx, my)) {
            if (!searchFocused) searchChangedSinceBlur = false;
            searchFocused = true;
            searchOpen = true;
            if (btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                searchWidget.setValue("");
                searchText = "";
            } else {
                searchWidget.mouseClicked(mx, my, btn);
            }
            return;
        }

        int foldX = panelX + panelWidth() - 16;
        if (mx >= foldX && mx < foldX + 14 && my >= panelY + 2 && my < panelY + 16) {
            panelHidden = true;
            RSIntegrationConfig.RS_SIDE_PANEL_HIDDEN.set(true);
            onSearchBlur();
            return;
        }

        if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT && headerContains(mx, my) && !searchBarContains(mx, my)) {
            movingPanel = true;
            moveStartMouseX = (int) mx;
            moveStartMouseY = (int) my;
            moveStartPanelX = panelX;
            moveStartPanelY = panelY;
            onSearchBlur();
            return;
        }

        if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT && sideButtonContains(mx, my)) {
            int relY = (int) my - (panelY + HEADER_H + 1);
            int i = relY / SIDE_BTN_PITCH;
            if (i >= 0 && i < 5) handleSideButtonClick(i);
            onSearchBlur();
            return;
        }

        onSearchBlur();

        if (clickLockTicks > 0) return;
        if (!networkAvailable) return;

        // Scrollbar
        int scrollX = panelX + SCROLLBAR_X;
        int scrollY = panelY + HEADER_H;
        int scrollH = visibleRows * SLOT_SIZE;
        if (needsScrollbar() && mx >= scrollX && mx < scrollX + 12
                && my >= scrollY && my < scrollY + scrollH) {
            scrolling = true;
            updateScrollFromMouse(my);
            return;
        }

        // Grid slots
        int itemLeft = panelX + GRID_ITEM_X;
        int itemTop  = panelY + HEADER_H;
        if (mx < itemLeft || mx >= itemLeft + COLUMNS * SLOT_SIZE
                || my < itemTop || my >= itemTop + visibleRows * SLOT_SIZE) return;

        int col = ((int) mx - itemLeft) / SLOT_SIZE;
        int row = ((int) my - itemTop) / SLOT_SIZE;
        if (col < 0 || col >= COLUMNS || row < 0 || row >= visibleRows) return;

        // ── Insert carried item (server-authoritative, no client prediction) ──
        ItemStack carried = mc.player.containerMenu.getCarried();
        if (!carried.isEmpty()) {
            if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                RSSidePanelNetworkHandler.sendInsert(carried, false);
            } else if (btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                RSSidePanelNetworkHandler.sendInsert(carried, true);
            }
            clickLockTicks = 5;
            return;
        }

        // ── Extract / drag from occupied slot ──────────────────────
        int dIdx = (scrollRow + row) * COLUMNS + col;
        if (dIdx < 0 || dIdx >= displayList.size()) return;
        ItemStack clickedItem = displayList.get(dIdx);
        if (clickedItem.isEmpty()) return;

        // Left drag start (Ctrl+Click = distribute drag)
        if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT && Screen.hasControlDown()) {
            gridDragging = true;
            gridDragKeys.clear();
            gridDragKeys.add(keyOf(clickedItem));
            gridDragCrossedSlots = false;
            return;
        }

        // Matches RS native: left=1, right=half, shift=full-stack-to-inventory
        int extractCount;
        byte action;
        if (Screen.hasShiftDown()) {
            action = RSSidePanelClickPacket.ACTION_EXTRACT_MAX;
            extractCount = Math.min(clickedItem.getMaxStackSize(), clickedItem.getCount());
        } else if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            action = RSSidePanelClickPacket.ACTION_EXTRACT_ONE;
            extractCount = 1;
        } else {
            action = RSSidePanelClickPacket.ACTION_EXTRACT_STACK;
            extractCount = Math.max(1, clickedItem.getCount() / 2);
        }
        RSSidePanelNetworkHandler.sendClick(clickedItem, action, Screen.hasShiftDown());
        clickLockTicks = 5;

        if (extractCount > 0) {
            String pk = keyOf(clickedItem);
            ItemEntry cur = itemMap.get(pk);
            if (cur != null) {
                pendingExtractions.put(pk, new PendingExtraction(cur.stack, cur.timestamp, cur.craftable));
                int remaining = cur.stack.getCount() - extractCount;
                if (remaining <= 0) {
                    itemMap.remove(pk);
                } else {
                    cur.stack.setCount(remaining);
                }
                displayDirty = true;
            }
        }
    }

    @SuppressWarnings("resource")
    private static void onScreenMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (movingPanel) {
            movingPanel = false;
            if (panelHidden) {
                int dx = (int) event.getMouseX() - moveStartMouseX;
                int dy = (int) event.getMouseY() - moveStartMouseY;
                if (Math.abs(dx) < 3 && Math.abs(dy) < 3) {
                    // Was a click, not a drag — expand
                    panelHidden = false;
                    RSIntegrationConfig.RS_SIDE_PANEL_HIDDEN.set(false);
                    searchText = "";
                    if (searchWidget != null) searchWidget.setValue("");
                    searchFocused = false;
                    scrollRow = 0;
                    RSSidePanelNetworkHandler.sendRequestSync();
                }
            }
            savePosition();
            event.setCanceled(true);
            return;
        }
        if (scrolling) {
            scrolling = false;
            event.setCanceled(true);
            return;
        }
        if (gridDragging) {
            if (gridDragKeys.size() > 1 || gridDragCrossedSlots) {
                List<ItemStack> dragItems = new ArrayList<>();
                for (String key : gridDragKeys) {
                    ItemEntry e = itemMap.get(key);
                    if (e != null && !e.stack.isEmpty()) dragItems.add(e.stack);
                }
                if (!dragItems.isEmpty())
                    RSSidePanelNetworkHandler.sendDragDistribute(dragItems);
            } else if (!gridDragKeys.isEmpty()) {
                String key = gridDragKeys.iterator().next();
                ItemEntry e = itemMap.get(key);
                if (e != null) {
                    RSSidePanelNetworkHandler.sendClick(e.stack, RSSidePanelClickPacket.ACTION_EXTRACT_MAX, false);
                }
            }
            gridDragging = false;
            gridDragKeys.clear();
            event.setCanceled(true);
            return;
        }

        if (!panelVisible || panelHidden) return;
        if (anyPanelContains(event.getMouseX(), event.getMouseY()) || clickLockTicks > 0)
            event.setCanceled(true);
    }

    @SuppressWarnings("resource")
    private static void onScreenMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!panelVisible || panelHidden) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        double mx = event.getMouseX(), my = event.getMouseY();
        if (!(movingPanel || scrolling || gridDragging || anyPanelContains(mx, my)))
            return;
        event.setCanceled(true);

        if (movingPanel) {
            int sw = mc.getWindow().getGuiScaledWidth();
            int sh = mc.getWindow().getGuiScaledHeight();
            int pw = panelWidth(), ph = panelHeight();
            panelX = Mth.clamp(moveStartPanelX + ((int) mx - moveStartMouseX), 0, sw - pw);
            panelY = Mth.clamp(moveStartPanelY + ((int) my - moveStartMouseY), 0, sh - ph);
            return;
        }

        if (scrolling) {
            updateScrollFromMouse(my);
            return;
        }

        if (gridDragging) {
            int itemLeft = panelX + GRID_ITEM_X;
            int itemTop  = panelY + HEADER_H;
            int col = ((int) mx - itemLeft) / SLOT_SIZE;
            int row = ((int) my - itemTop) / SLOT_SIZE;
            if (col >= 0 && col < COLUMNS && row >= 0 && row < visibleRows) {
                int dIdx = (scrollRow + row) * COLUMNS + col;
                if (dIdx >= 0 && dIdx < displayList.size()) {
                    ItemStack s = displayList.get(dIdx);
                    if (!s.isEmpty()) {
                        String k = keyOf(s);
                        if (!gridDragKeys.contains(k))
                            gridDragCrossedSlots = true;
                        gridDragKeys.add(k);
                    }
                }
            }
        }
    }

    @SuppressWarnings("resource")
    private static void onScreenMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!panelVisible || panelHidden) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen == null) return;

        double mx = event.getMouseX(), my = event.getMouseY();
        if (clickLockTicks > 0) { event.setCanceled(true); return; }
        if (!anyPanelContains(mx, my)) return;
        event.setCanceled(true);

        double delta = event.getScrollDelta();
        if (delta < 0) scrollRow++;
        else if (delta > 0) scrollRow--;
        clampScroll();
    }

    // ── HUD scroll (no screen open) ─────────────────────────────

    @SuppressWarnings("resource")
    private static void onInputMouseScrolled(InputEvent.MouseScrollingEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !panelVisible || panelHidden) return;
        if (mc.screen != null) return; // let ScreenEvent handler take it

        double mx = lastMouseX, my = lastMouseY;
        if (!anyPanelContains(mx, my)) return;
        event.setCanceled(true);

        double delta = event.getScrollDelta();
        if (delta < 0) scrollRow++;
        else if (delta > 0) scrollRow--;
        clampScroll();
    }

    // ── helpers ─────────────────────────────────────────────────

    private static void updateScrollFromMouse(double my) {
        int totalRows = getTotalRows();
        int maxScroll = Math.max(0, totalRows - visibleRows);
        if (maxScroll <= 0) { scrollRow = 0; return; }
        int trackY = panelY + HEADER_H;
        int trackH = visibleRows * SLOT_SIZE;
        double t = (my - trackY) / Math.max(1, trackH);
        t = Mth.clamp(t, 0, 1);
        scrollRow = (int) Math.round(t * maxScroll);
        clampScroll();
    }

    private static void handleSideButtonClick(int idx) {
        switch (idx) {
            case 0: viewType = (viewType + 1) % 3; scrollRow = 0; break;
            case 1: sortAsc = !sortAsc; break;
            case 2: sortMode = (sortMode + 1) % 4; break;
            case 3:
                boolean wasJei = searchMode >= 2;
                searchMode = (searchMode + 1) % 6;
                if (searchMode >= 2) {
                    if (com.huanghuang.rsintegration.integration.RSJeiPlugin.getRuntime() == null)
                        searchMode = 0;
                }
                if (!wasJei && searchMode >= 2) {
                    lastJeiFilterText = "";
                    pullJeiFilter();
                }
                break;
            case 4: gridSize = (gridSize + 1) % 4; scrollRow = 0; break;
        }
        displayDirty = true;
    }

    // ── tick ────────────────────────────────────────────────────

    @SuppressWarnings("resource")
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (event.phase == TickEvent.Phase.START) return;
        if (!panelVisible) return;

        tickCounter++;
        if (clickLockTicks > 0) clickLockTicks--;
        // Safety: reset gridDragging if mouse button isn't held (prevents stuck sorts)
        if (gridDragging && org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_RELEASE)
            gridDragging = false;

        int guiScale = (int) mc.getWindow().getGuiScale();
        int mx = (int) (mc.mouseHandler.xpos() / guiScale);
        int my = (int) (mc.mouseHandler.ypos() / guiScale);
        hoveredSideButton = -1;

        if (mc.screen == null) {
            if (scrolling && !panelHidden) updateScrollFromMouse(my);
            if (movingPanel) {
                int sw = mc.getWindow().getGuiScaledWidth();
                int sh = mc.getWindow().getGuiScaledHeight();
                panelX = Mth.clamp(moveStartPanelX + (mx - moveStartMouseX), 0, sw - panelWidth());
                panelY = Mth.clamp(moveStartPanelY + (my - moveStartMouseY), 0, sh - panelHeight());
            }
        }

        if (searchMode >= 2 && tickCounter % 5 == 0) pullJeiFilter();

        // Periodic silent full sync to correct any client-side drift (60s)
        if (tickCounter % 1200 == 0 && networkAvailable && !panelHidden) {
            RSSidePanelNetworkHandler.sendRequestSync();
        }

        // Timeout stale pending extractions (3s).  Reverts itemMap to the
        // pre-prediction snapshot and marks display dirty so the HUD reflects
        // reality even if the server delta never arrived.
        if (!pendingExtractions.isEmpty() && tickCounter % 20 == 0) {
            long now = System.currentTimeMillis();
            var it = pendingExtractions.entrySet().iterator();
            while (it.hasNext()) {
                var pe = it.next();
                if (now - pe.getValue().createdAt > 3000) {
                    // Delta never arrived — revert to pre-prediction snapshot.
                    PendingExtraction p = pe.getValue();
                    if (p.previousStack.getCount() > 0) {
                        itemMap.put(pe.getKey(), new ItemEntry(p.previousStack.copy(), p.timestamp, p.craftable));
                    } else {
                        itemMap.remove(pe.getKey());
                    }
                    it.remove();
                    displayDirty = true;
                }
            }
        }
    }

    // ── data helpers ────────────────────────────────────────────

    private static int getTotalRows() {
        ensureDisplayList();
        return (int) Math.ceil(displayList.size() / (double) COLUMNS);
    }

    private static void clampScroll() {
        int max = Math.max(0, getTotalRows() - visibleRows);
        if (scrollRow < 0) scrollRow = 0;
        if (scrollRow > max) scrollRow = max;
    }

    // ── lazy display list rebuild ───────────────────────────────

    /** Called at the start of every render frame. Only rebuilds when dirty. */
    private static void ensureDisplayList() {
        if (!displayDirty) return;
        rebuildDisplayList();
    }

    private static boolean matchesPinyin(String itemName, String query) {
        try {
            String pinyin = PinyinHelper.convertToPinyinString(itemName, "",
                    PinyinFormat.WITHOUT_TONE);
            return pinyin.toLowerCase().contains(query.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("resource")
    private static void rebuildDisplayList() {
        displayDirty = false;
        List<ItemStack> list = new ArrayList<>();
        for (ItemEntry e : itemMap.values()) {
            if (!e.stack.isEmpty()) list.add(e.stack);
        }

        // Search filter
        if (!searchText.isEmpty()) {
            String query = searchText.trim();
            if (query.startsWith("@")) {
                String mq = query.substring(1).toLowerCase();
                list.removeIf(stack -> {
                    var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    if (key == null) return true;
                    String modId = key.getNamespace().toLowerCase();
                    if (modId.contains(mq)) return false;
                    String modName = ModList.get()
                            .getModContainerById(key.getNamespace())
                            .map(c -> c.getModInfo().getDisplayName().toLowerCase()).orElse("");
                    return !modName.contains(mq);
                });
            } else if (query.startsWith("#")) {
                String tq = query.substring(1).toLowerCase();
                var mc = Minecraft.getInstance();
                list.removeIf(stack -> {
                    for (Component line : stack.getTooltipLines(mc.player,
                            mc.options.advancedItemTooltips
                                    ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                                    : net.minecraft.world.item.TooltipFlag.Default.NORMAL)) {
                        String text = ChatFormatting.stripFormatting(line.getString());
                        if (text != null && text.toLowerCase().contains(tq)) return false;
                    }
                    return true;
                });
            } else {
                // Auto-detect regex: try compiling as pattern first; fall back to plain text
                try {
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile(query,
                            java.util.regex.Pattern.CASE_INSENSITIVE);
                    list.removeIf(stack -> {
                        String name = stack.getHoverName().getString();
                        if (p.matcher(name).find()) return false;
                        return !matchesPinyin(name, query);
                    });
                } catch (java.util.regex.PatternSyntaxException e) {
                    String lower = query.toLowerCase();
                    list.removeIf(stack -> {
                        String name = stack.getHoverName().getString();
                        if (name.toLowerCase().contains(lower)) return false;
                        if (matchesPinyin(name, lower)) return false;
                        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                        return key == null || !key.getPath().contains(lower);
                    });
                }
            }
        }

        // View type filter
        if (viewType != 0) {
            list.removeIf(stack -> {
                ItemEntry e = itemMap.get(keyOf(stack));
                boolean craftable = e != null && e.craftable;
                return viewType == 1 ? craftable : !craftable;
            });
        }

        // Sort (only when canSort)
        if (canSort()) {
            switch (sortMode) {
                case 0 -> {
                    if (sortAsc) list.sort(Comparator.comparing(s -> s.getHoverName().getString().toLowerCase()));
                    else list.sort(Comparator.comparing((ItemStack s) -> s.getHoverName().getString().toLowerCase()).reversed());
                }
                case 1 -> {
                    if (sortAsc) list.sort(Comparator.comparingInt(ItemStack::getCount));
                    else list.sort((a, b) -> Integer.compare(b.getCount(), a.getCount()));
                }
                case 2 -> list.sort((a, b) -> {
                    var ka = ForgeRegistries.ITEMS.getKey(a.getItem());
                    var kb = ForgeRegistries.ITEMS.getKey(b.getItem());
                    String ia = ka != null ? ka.toString() : "zzz:zzz";
                    String ib = kb != null ? kb.toString() : "zzz:zzz";
                    return sortAsc ? ia.compareToIgnoreCase(ib) : ib.compareToIgnoreCase(ia);
                });
                case 3 -> {
                    if (sortAsc) list.sort(Comparator.comparingLong(
                            s -> {
                                ItemEntry e = itemMap.get(keyOf(s));
                                return e != null ? e.timestamp : 0L;
                            }));
                    else list.sort((a, b) -> {
                        ItemEntry ea = itemMap.get(keyOf(a));
                        ItemEntry eb = itemMap.get(keyOf(b));
                        return Long.compare(
                                eb != null ? eb.timestamp : 0L,
                                ea != null ? ea.timestamp : 0L);
                    });
                }
            }
        }

        displayList.clear();
        displayList.addAll(list);
    }

    private static String formatCount(int count) {
        if (count >= 1_000_000_000)
            return (count / 1_000_000_000) + "." + ((count / 100_000_000) % 10) + "B";
        if (count >= 1_000_000) {
            if (count >= 100_000_000) return (count / 1_000_000) + "M";
            return (count / 1_000_000) + "." + ((count / 100_000) % 10) + "M";
        }
        if (count >= 1_000) {
            if (count >= 100_000) return (count / 1_000) + "K";
            return (count / 1_000) + "." + ((count / 100) % 10) + "K";
        }
        return String.valueOf(count);
    }
}
