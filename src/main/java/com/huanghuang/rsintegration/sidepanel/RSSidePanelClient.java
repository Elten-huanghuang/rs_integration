package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
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
import org.lwjgl.glfw.GLFW;

import java.util.*;

@OnlyIn(Dist.CLIENT)
public final class RSSidePanelClient {

    // ── layout constants ───────────────────────────────────────────
    private static final int SLOT_SIZE  = 18;
    private static final int COLUMNS    = 9;
    private static final int SCROLLBAR_W = 6;
    private static final int BUTTON_SIZE = 14;
    private static final int SIDE_BTN_SIZE = 18;   // RS side button size (icons.png)
    private static final int SIDE_BTN_ICON = 16;
    private static final int SIDE_BTN_GAP  = 2;    // 18+2=20 pitch
    private static final int GRID_CONTENT_W = 162; // 9 * 18
    private static final int SIDE_COL_W = 20;
    private static final int RS_GRID_LEFT = 7;
    private static final int RS_SEARCH_X = 81;
    private static final int RS_SEARCH_Y = 7;
    private static final int RS_SEARCH_W = 82;
    private static final int RS_TOP_H = 19;
    private static final int BOTTOM_STRIP_H = 4;
    private static final int HEADER_H = RS_TOP_H;  // top texture strip

    private static final ResourceLocation RS_GRID_TEX =
            new ResourceLocation("refinedstorage", "textures/gui/grid.png");
    private static final ResourceLocation RS_ICONS_TEX =
            new ResourceLocation("refinedstorage", "textures/gui/icons.png");

    private static final float RENDER_Z = 500.0f;

    private static final Set<String> RS_SCREEN_CLASSES = Set.of(
            "com.refinedmods.refinedstorage.screen.grid.GridScreen",
            "com.refinedmods.refinedstorage.screen.CraftingMonitorScreen",
            "com.refinedmods.refinedstorage.screen.ControllerScreen"
    );

    // ── state ──────────────────────────────────────────────────────
    static boolean panelVisible;
    static boolean panelHidden = true;   // starts collapsed
    static int panelX = 100, panelY = 100;

    static boolean networkAvailable;
    static String networkName = "";
    static List<ItemStack> cachedItems = new ArrayList<>();
    static List<Long> cachedTimestamps = new ArrayList<>();
    static List<Boolean> cachedCraftableFlags = new ArrayList<>();
    static int totalSlotCount;

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
    static int searchAnimTicks;

    // Drag / move / scrollbar states
    static boolean movingPanel;
    static int moveStartMouseX, moveStartMouseY;
    static int moveStartPanelX, moveStartPanelY;
    static boolean scrolling;
    static boolean gridDragging;
    static int gridDragButton;
    static final Set<Integer> gridDragSlots = new LinkedHashSet<>();
    static boolean gridDragCrossedSlots;

    static int hoveredSideButton = -1;
    static int hoveredSlotIndex = -1;
    static int lastMouseX, lastMouseY;

    static KeyMapping KEY_TOGGLE_PANEL;

    private RSSidePanelClient() {}

    // ── init ──────────────────────────────────────────────────────

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
        bus.addListener(EventPriority.HIGH, RSSidePanelClient::onKeyInput);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenMousePressed);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenMouseReleased);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenMouseDragged);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenMouseScrolled);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenKeyPressed);
        bus.addListener(EventPriority.HIGHEST, RSSidePanelClient::onScreenCharTyped);

        RSIntegrationMod.LOGGER.info("[RSI] SidePanel client initialized.");
    }

    // ── save ──────────────────────────────────────────────────────

    static void savePosition() {
        RSIntegrationConfig.RS_SIDE_PANEL_X.set(panelX);
        RSIntegrationConfig.RS_SIDE_PANEL_Y.set(panelY);
        RSIntegrationConfig.RS_SIDE_PANEL_HIDDEN.set(panelHidden);
        RSIntegrationConfig.CLIENT_SPEC.save();
    }

    // ── sync callback ─────────────────────────────────────────────

    static void onSyncReceived(RSSidePanelSyncPacket packet) {
        clickLockTicks = 0;
        cachedItems.clear();
        cachedItems.addAll(packet.items);
        cachedTimestamps.clear();
        cachedTimestamps.addAll(packet.timestamps);
        cachedCraftableFlags.clear();
        cachedCraftableFlags.addAll(packet.craftableFlags);
        totalSlotCount = packet.totalSlotCount;
        networkAvailable = packet.networkAvailable;
        networkName = packet.networkName;
        clampScroll();
    }

    // ── PanelRect helper ──────────────────────────────────────────

    private static int panelW() { return SIDE_COL_W + GRID_CONTENT_W + (needsScrollbar() ? SCROLLBAR_W + 6 : 0); }
    private static int panelH() { return HEADER_H + visibleRows * SLOT_SIZE + BOTTOM_STRIP_H; }
    private static boolean needsScrollbar() {
        return getTotalRows() > visibleRows;
    }

    private static Rect2i panelRect() {
        return new Rect2i(panelX, panelY, panelW(), panelH());
    }

    private static boolean panelContains(double mx, double my) {
        Rect2i r = panelRect();
        return mx >= r.getX() && mx < r.getX() + r.getWidth()
                && my >= r.getY() && my < r.getY() + r.getHeight();
    }

    private static boolean gridSlotsContains(double mx, double my) {
        // The grid slot area: from side-col-right-edge, top-header-bottom, spanning 9*18 wide, visibleRows*18 high
        int gx = panelX + SIDE_COL_W + RS_GRID_LEFT;
        int gy = panelY + HEADER_H;
        return mx >= gx && mx < gx + COLUMNS * SLOT_SIZE
                && my >= gy && my < gy + visibleRows * SLOT_SIZE;
    }

    private static boolean scrollbarContains(double mx, double my) {
        if (!needsScrollbar()) return false;
        int sx = panelX + SIDE_COL_W + GRID_CONTENT_W + 3;
        int sy = panelY + HEADER_H;
        return mx >= sx && mx < sx + SCROLLBAR_W
                && my >= sy && my < sy + visibleRows * SLOT_SIZE;
    }

    private static boolean sideButtonContains(double mx, double my) {
        int bx = panelX + 1;
        int by = panelY + 6;
        return mx >= bx && mx < bx + SIDE_BTN_SIZE
                && my >= by && my < by + 5 * (SIDE_BTN_SIZE + SIDE_BTN_GAP);
    }

    private static boolean searchBarContains(double mx, double my) {
        if (!searchOpen) return false;
        int sx = panelX + SIDE_COL_W + RS_SEARCH_X;
        int sy = panelY + RS_SEARCH_Y;
        return mx >= sx && mx < sx + RS_SEARCH_W && my >= sy && my < sy + 9;
    }

    private static boolean headerContains(double mx, double my) {
        Rect2i r = panelRect();
        return mx >= r.getX() && mx < r.getX() + r.getWidth()
                && my >= r.getY() && my < r.getY() + HEADER_H;
    }

    private static boolean interactiveContains(double mx, double my) {
        if (panelContains(mx, my)) return true;
        if (searchBarContains(mx, my)) return true;
        return false;
    }

    // ── key input ─────────────────────────────────────────────────

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
        if (!panelVisible || panelHidden || !searchFocused) return;
        if (Minecraft.getInstance().screen != null) return;
        handleTextInput(event.getKey(), event.getScanCode(), event.getAction());
    }

    @SuppressWarnings("resource")
    private static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!panelVisible || panelHidden || !searchFocused) return;
        handleTextInput(event.getKeyCode(), event.getScanCode(), GLFW.GLFW_PRESS);
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

    private static void handleTextInput(int key, int scanCode, int action) {
        if (action != GLFW.GLFW_PRESS) return;
        if (searchWidget == null) return;

        if (key == GLFW.GLFW_KEY_ESCAPE) { onSearchBlur(); return; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if ((searchMode % 2) == 1) {
                List<ItemStack> filtered = getFilteredAndSortedList();
                if (!filtered.isEmpty()) {
                    int idx = cachedItems.indexOf(filtered.get(0));
                    if (idx >= 0) RSSidePanelNetworkHandler.sendClick(idx,
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
        String ch = glfwKeyToChar(key, scanCode);
        if (ch != null) {
            for (int i = 0; i < ch.length(); i++)
                searchWidget.charTyped(ch.charAt(i),
                        Screen.hasControlDown() ? GLFW.GLFW_MOD_CONTROL : 0);
            pushJeiFilter();
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
        searchAnimTicks = 0;
    }

    private static void pushJeiFilter() {
        if (searchMode < 4) return;
        try {
            Class<?> cls = Class.forName("com.refinedmods.refinedstorage.integration.jei.RSJeiPlugin");
            var rt = cls.getMethod("getRuntime").invoke(null);
            if (rt != null) {
                var f = rt.getClass().getMethod("getIngredientFilter").invoke(rt);
                if (f != null) f.getClass().getMethod("setFilterText", String.class).invoke(f, searchText);
            }
        } catch (Exception ignored) {}
    }

    private static void pullJeiFilter() {
        if (searchMode < 4) return;
        try {
            Class<?> cls = Class.forName("com.refinedmods.refinedstorage.integration.jei.RSJeiPlugin");
            var rt = cls.getMethod("getRuntime").invoke(null);
            if (rt != null) {
                var f = rt.getClass().getMethod("getIngredientFilter").invoke(rt);
                if (f != null) {
                    String t = (String) f.getClass().getMethod("getFilterText").invoke(f);
                    if (t != null && !t.equals(lastJeiFilterText)) {
                        lastJeiFilterText = t;
                        if (!t.equals(searchText)) {
                            searchText = t;
                            if (searchWidget != null) searchWidget.setValue(t);
                            historyIndex = -1;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // ── rendering ─────────────────────────────────────────────────

    @SuppressWarnings("resource")
    private static void onRenderGuiPost(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!panelVisible) return;
        if (mc.screen != null && RS_SCREEN_CLASSES.contains(mc.screen.getClass().getName())) return;

        int guiScale = (int) mc.getWindow().getGuiScale();
        lastMouseX = (int) (mc.mouseHandler.xpos() / guiScale);
        lastMouseY = (int) (mc.mouseHandler.ypos() / guiScale);

        visibleRows = gridSizeToRows(mc.getWindow().getGuiScaledHeight());

        var pose = event.getGuiGraphics().pose();
        pose.pushPose();
        pose.translate(0, 0, RENDER_Z);

        if (panelHidden) {
            drawCollapsedBar(event.getGuiGraphics());
        } else {
            drawPanel(event.getGuiGraphics(), mc);
        }

        pose.popPose();
    }

    private static int gridSizeToRows(int screenH) {
        return switch (gridSize) {
            case 0 -> Math.max(3, (screenH - panelY - HEADER_H - BOTTOM_STRIP_H) / SLOT_SIZE);
            case 1 -> 3;
            case 2 -> 5;
            default -> 8;
        };
    }

    @SuppressWarnings("resource")
    private static void drawPanel(GuiGraphics g, Minecraft mc) {
        var font = mc.font;
        int px = panelX, py = panelY;
        int pw = panelW(), ph = panelH();

        // Clamp to screen
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        panelX = Math.max(0, Math.min(panelX, sw - pw));
        panelY = Math.max(0, Math.min(panelY, sh - ph));

        // Background fill
        g.fill(px, py, px + pw, py + ph, 0xCC1B1B1B);
        // Top border
        g.fill(px, py, px + pw, py + 1, 0xFF555555);
        // Bottom border
        g.fill(px, py + ph - 1, px + pw, py + ph, 0xFF555555);
        // Left border
        g.fill(px, py, px + 1, py + ph, 0xFF555555);
        // Right border
        g.fill(px + pw - 1, py, px + pw, py + ph, 0xFF555555);

        // Side column background (slightly different shade)
        g.fill(px + 1, py + 1, px + SIDE_COL_W - 1, py + ph - 1, 0xFF222222);

        // Grid slot area background
        int gx = px + SIDE_COL_W;
        int gy = py + HEADER_H;
        int gridW = COLUMNS * SLOT_SIZE;
        int gridH = visibleRows * SLOT_SIZE;
        g.fill(gx, gy, gx + gridW, gy + gridH, 0xFF3A3A3A);

        // Draw grid.png header strip as decoration on top
        g.blit(RS_GRID_TEX, px, py, 0, 0, SIDE_COL_W, HEADER_H); // side column header
        g.blit(RS_GRID_TEX, gx, py, SIDE_COL_W, 0, GRID_CONTENT_W, HEADER_H); // content header
        // Right extension
        if (needsScrollbar()) {
            g.fill(gx + GRID_CONTENT_W, py, px + pw, py + HEADER_H, 0xFF3A3A3A);
        } else {
            g.fill(gx + GRID_CONTENT_W, py, px + pw, py + HEADER_H, 0xFF272727);
        }

        // Bottom strip
        int by = py + HEADER_H + gridH;
        g.fill(gx, by, px + pw, by + BOTTOM_STRIP_H, 0xFF2A2A2A);

        // Network name
        String label;
        int labelColor;
        if (networkAvailable) {
            label = !networkName.isEmpty() ? networkName
                    : Component.translatable("item.sophisticatedbackpacks.rs_network.tooltip").getString();
            labelColor = 0xFF7BAAF7;
        } else {
            label = Component.translatable("rsi.side_panel.no_network").getString();
            labelColor = 0xFFFF5555;
        }
        int maxLabelW = RS_SEARCH_X - RS_GRID_LEFT - 4;
        String trimmed = font.plainSubstrByWidth(label, maxLabelW);
        g.drawString(font, trimmed, gx + RS_GRID_LEFT, py + RS_SEARCH_Y + 1, labelColor);

        // Search widget
        if (searchWidget == null) {
            searchWidget = new EditBox(font, gx + RS_SEARCH_X, py + RS_SEARCH_Y,
                    RS_SEARCH_W, 9, Component.translatable("rsi.side_panel.search_hint"));
            searchWidget.setBordered(false);
            searchWidget.setTextColor(0xFFE0E0E0);
            searchWidget.setValue(searchText);
            searchWidget.setResponder(t -> { searchText = t; searchChangedSinceBlur = true; });
        }
        searchWidget.setX(gx + RS_SEARCH_X);
        searchWidget.setY(py + RS_SEARCH_Y);
        searchWidget.setFocused(searchFocused);
        searchWidget.render(g, lastMouseX, lastMouseY, 0);

        // Search animation
        if (searchFocused || !searchText.isEmpty()) {
            if (searchAnimTicks < 12) searchAnimTicks++;
        } else {
            if (searchAnimTicks > 0) searchAnimTicks--;
        }
        if (searchAnimTicks > 0 && searchAnimTicks < 12) {
            float progress = searchAnimTicks / 12.0f;
            progress = progress < 0.5f ? 4f * progress * progress * progress
                    : 1f - (float) Math.pow(-2f * progress + 2f, 3) / 2f;
            int sfX = gx + RS_SEARCH_X, sfY = py + RS_SEARCH_Y;
            g.fill(sfX, sfY, sfX + RS_SEARCH_W, sfY + 10,
                    0x40FFFFFF & ((int)(60 * (1f - progress)) << 24));
        }

        // Side buttons (RS icons on the left)
        int sby = py + 6;
        for (int i = 0; i < 5; i++) {
            drawSideButton(g, px + 1, sby + i * (SIDE_BTN_SIZE + SIDE_BTN_GAP), i);
        }

        // Fold button (top-right corner)
        int foldX = px + pw - 14;
        g.fill(foldX, py + 2, foldX + 12, py + 14, 0x80000000);
        g.drawString(font, "X", foldX + 3, py + 3, 0xFFBBBBBB);

        // Items grid
        List<ItemStack> display = getFilteredAndSortedList();
        hoveredSlotIndex = -1;

        for (int row = 0; row < visibleRows; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                int dIdx = (scrollRow + row) * COLUMNS + col;
                if (dIdx >= display.size()) break;
                ItemStack stack = display.get(dIdx);
                if (stack.isEmpty()) continue;

                int ix = gx + RS_GRID_LEFT + col * SLOT_SIZE;
                int iy = gy + row * SLOT_SIZE;

                boolean hovered = lastMouseX >= ix && lastMouseX < ix + SLOT_SIZE
                        && lastMouseY >= iy && lastMouseY < iy + SLOT_SIZE;

                // Slot background
                int bgColor;
                if (gridDragging && gridDragSlots.contains(cachedItems.indexOf(stack)))
                    bgColor = 0xFF66B14C;  // green tint
                else if (hovered)
                    bgColor = 0xFF7777C7;  // highlight
                else
                    bgColor = 0xFF3A3A3A;  // default

                g.fill(ix, iy, ix + 17, iy + 17, bgColor);
                g.fill(ix + 1, iy + 1, ix + 16, iy + 16, 0xFF202020);

                g.renderItem(stack, ix + 1, iy + 1);
                g.renderItemDecorations(font, stack, ix + 1, iy + 1, "");

                if (stack.getCount() > 1) {
                    String cnt = formatCount(stack.getCount());
                    var p = g.pose();
                    p.pushPose();
                    p.translate(ix + 16, iy + 16, 300);
                    p.scale(0.5f, 0.5f, 1);
                    int tw = font.width(cnt);
                    g.drawString(font, cnt, -tw, -font.lineHeight + 1, 0xFFFFFFFF);
                    p.popPose();
                }

                if (hovered) hoveredSlotIndex = dIdx;
            }
        }

        // Scrollbar
        drawScrollbar(g);

        // Resize hints (bottom-right corner)
        int rx = px + pw - 5, ry = py + ph - 5;
        g.fill(rx, py + 8, rx + 1, ry, 0xFF505050);
        g.fill(px + 8, ry, rx, ry + 1, 0xFF505050);

        // Tooltips
        if (hoveredSlotIndex >= 0 && hoveredSlotIndex < display.size()) {
            ItemStack hs = display.get(hoveredSlotIndex);
            if (!hs.isEmpty())
                renderItemTooltip(g, font, hs, lastMouseX, lastMouseY);
        }
        if (hoveredSideButton >= 0)
            renderSideButtonTooltip(g, font, hoveredSideButton);
    }

    private static void drawSideButton(GuiGraphics g, int bx, int by, int idx) {
        boolean hovered = lastMouseX >= bx && lastMouseX < bx + SIDE_BTN_SIZE
                && lastMouseY >= by && lastMouseY < by + SIDE_BTN_SIZE;
        if (hovered) hoveredSideButton = idx;

        // Button background from RS icons.png
        int bgV = hovered ? 35 : 16;
        g.blit(RS_ICONS_TEX, bx, by, 238, bgV, SIDE_BTN_SIZE, SIDE_BTN_SIZE);

        // Icon
        int u = 0, v = 0;
        switch (idx) {
            case 0: // sort direction
                u = sortAsc ? 0 : 16; v = 16; break;
            case 1: // sort type
                if (sortMode == 3) { u = 48; v = 48; }
                else { u = sortMode * 16; v = 32; }
                break;
            case 2: // search mode
                u = (searchMode % 2 == 1) ? 16 : 0; v = 96; break;
            case 3: // grid size
                u = switch (gridSize) { case 0 -> 112; case 1 -> 64; case 2 -> 80; default -> 96; };
                v = 64;
                break;
            case 4: // view type
                u = viewType * 16; v = 112; break;
            default: return;
        }
        g.blit(RS_ICONS_TEX, bx + 1, by + 1, u, v, SIDE_BTN_ICON, SIDE_BTN_ICON);
    }

    private static void drawScrollbar(GuiGraphics g) {
        int totalRows = getTotalRows();
        if (totalRows <= visibleRows) return;

        int maxScroll = Math.max(0, totalRows - visibleRows);
        if (maxScroll <= 0) return;

        int sx = panelX + SIDE_COL_W + GRID_CONTENT_W + 3;
        int sy = panelY + HEADER_H;
        int trackH = visibleRows * SLOT_SIZE;

        int thumbH = Math.max(12, trackH * visibleRows / Math.max(1, totalRows));
        int trackAvail = Math.max(1, trackH - thumbH);
        int thumbY = sy + (int) Math.round((double) trackAvail * scrollRow / maxScroll);

        // Track
        g.fill(sx, sy, sx + SCROLLBAR_W, sy + trackH, 0xFF202020);
        // Thumb
        g.fill(sx + 1, thumbY, sx + SCROLLBAR_W - 1, thumbY + thumbH, 0xFF9A9A9A);
    }

    private static void drawCollapsedBar(GuiGraphics g) {
        int x = panelX, y = panelY;
        int w = 40, h = 18;
        // Clamp
        var mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        panelX = Math.max(0, Math.min(panelX, sw - w));
        panelY = Math.max(0, Math.min(panelY, sh - h));

        g.fill(x, y, x + w, y + h, 0xCC1B1B1B);
        g.fill(x, y, x + w, y + 1, 0xFF555555);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF555555);
        g.fill(x, y, x + 1, y + h, 0xFF555555);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF555555);

        var font = Minecraft.getInstance().font;
        // Drag dots
        int dot = 0xFF505050;
        g.fill(x + 4, y + 6, x + 6, y + 8, dot);
        g.fill(x + 8, y + 6, x + 10, y + 8, dot);
        g.fill(x + 4, y + 10, x + 6, y + 12, dot);
        g.fill(x + 8, y + 10, x + 10, y + 12, dot);
        // Expand arrow
        int ax = x + 24;
        g.fill(ax, y + 5, ax + 8, y + 6, 0xFF888888);
        g.fill(ax, y + 12, ax + 8, y + 13, 0xFF888888);
        g.fill(ax, y + 5, ax + 1, y + 13, 0xFF888888);
        g.fill(ax + 7, y + 5, ax + 8, y + 13, 0xFF888888);
        g.fill(ax + 3, y + 7, ax + 6, y + 8, 0xFFAAAAAA);
        g.fill(ax + 4, y + 7, ax + 5, y + 10, 0xFFAAAAAA);
    }

    private static void renderItemTooltip(GuiGraphics g, net.minecraft.client.gui.Font font,
                                          ItemStack stack, int mx, int my) {
        List<Component> lines = new ArrayList<>(stack.getTooltipLines(
                Minecraft.getInstance().player,
                Minecraft.getInstance().options.advancedItemTooltips
                        ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                        : net.minecraft.world.item.TooltipFlag.Default.NORMAL));
        int stored = stack.getCount();
        if (stored > 0)
            lines.add(Component.literal("§7" + Component.translatable("rsi.side_panel.total",
                    formatCount(stored)).getString()));
        g.renderComponentTooltip(font, lines, mx, my);
    }

    private static void renderSideButtonTooltip(GuiGraphics g, net.minecraft.client.gui.Font font, int idx) {
        String label, mode;
        switch (idx) {
            case 0:
                label = Component.translatable("rsi.side_panel.btn.sort_dir").getString();
                mode = Component.translatable(sortAsc
                        ? "rsi.side_panel.btn.sort_dir.asc"
                        : "rsi.side_panel.btn.sort_dir.desc").getString();
                break;
            case 1:
                label = Component.translatable("rsi.side_panel.btn.sort_type").getString();
                String mk = switch (sortMode) {
                    case 0 -> "rsi.side_panel.btn.sort_type.count";
                    case 2 -> "rsi.side_panel.btn.sort_type.id";
                    case 3 -> "rsi.side_panel.btn.sort_type.last_modified";
                    default -> "rsi.side_panel.btn.sort_type.name";
                };
                mode = Component.translatable(mk).getString();
                break;
            case 2:
                label = Component.translatable("rsi.side_panel.btn.search_mode").getString();
                String sk = switch (searchMode) {
                    case 0 -> "rsi.side_panel.btn.search_mode.normal";
                    case 1 -> "rsi.side_panel.btn.search_mode.normal_auto";
                    case 2 -> "rsi.side_panel.btn.search_mode.regex";
                    case 3 -> "rsi.side_panel.btn.search_mode.regex_auto";
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
            case 3:
                label = Component.translatable("rsi.side_panel.btn.grid_size").getString();
                String gk = switch (gridSize) {
                    case 0 -> "rsi.side_panel.btn.grid_size.stretch";
                    case 1 -> "rsi.side_panel.btn.grid_size.small";
                    case 2 -> "rsi.side_panel.btn.grid_size.medium";
                    default -> "rsi.side_panel.btn.grid_size.large";
                };
                mode = Component.translatable(gk).getString();
                break;
            case 4:
                label = Component.translatable("rsi.side_panel.btn.view_type").getString();
                String vk = switch (viewType) {
                    case 1 -> "rsi.side_panel.btn.view_type.non_craftables";
                    case 2 -> "rsi.side_panel.btn.view_type.craftables";
                    default -> "rsi.side_panel.btn.view_type.all";
                };
                mode = Component.translatable(vk).getString();
                break;
            default: return;
        }
        g.renderComponentTooltip(font,
                List.of(Component.literal(label), Component.literal("§7" + mode)),
                lastMouseX, lastMouseY);
    }

    // ── screen mouse handlers ─────────────────────────────────────

    @SuppressWarnings("resource")
    private static void onScreenMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        var mc = Minecraft.getInstance();
        if (!panelVisible || mc.player == null) return;
        if (mc.screen != null && RS_SCREEN_CLASSES.contains(mc.screen.getClass().getName())) return;

        double mx = event.getMouseX(), my = event.getMouseY();
        int btn = event.getButton();

        // Collapsed: any click expands
        if (panelHidden) {
            if (mx >= panelX && mx < panelX + 40 && my >= panelY && my < panelY + 18) {
                panelHidden = false;
                RSIntegrationConfig.RS_SIDE_PANEL_HIDDEN.set(false);
                RSIntegrationConfig.CLIENT_SPEC.save();
                searchText = "";
                if (searchWidget != null) searchWidget.setValue("");
                searchFocused = false;
                scrollRow = 0;
                RSSidePanelNetworkHandler.sendRequestSync();
                event.setCanceled(true);
            }
            return;
        }

        // Outside panel? ignore (but let click-lock and drag states see release)
        if (!interactiveContains(mx, my)) return;
        event.setCanceled(true);

        // Search bar
        if (searchBarContains(mx, my)) {
            if (!searchFocused) searchChangedSinceBlur = false;
            searchFocused = true;
            if (btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                searchWidget.setValue("");
                searchText = "";
            } else {
                searchWidget.mouseClicked(mx, my, btn);
            }
            return;
        }

        // Fold button
        int foldX = panelX + panelW() - 14;
        if (mx >= foldX && mx < foldX + 12 && my >= panelY + 2 && my < panelY + 14) {
            panelHidden = true;
            RSIntegrationConfig.RS_SIDE_PANEL_HIDDEN.set(true);
            RSIntegrationConfig.CLIENT_SPEC.save();
            onSearchBlur();
            return;
        }

        // Header drag
        if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT && headerContains(mx, my)
                && !searchBarContains(mx, my) && !sideButtonContains(mx, my)) {
            movingPanel = true;
            moveStartMouseX = (int) mx;
            moveStartMouseY = (int) my;
            moveStartPanelX = panelX;
            moveStartPanelY = panelY;
            onSearchBlur();
            return;
        }

        // Side buttons
        if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT && sideButtonContains(mx, my)) {
            int relY = (int) my - (panelY + 6);
            int i = relY / (SIDE_BTN_SIZE + SIDE_BTN_GAP);
            if (i >= 0 && i < 5) handleSideButtonClick(i);
            onSearchBlur();
            return;
        }

        onSearchBlur();

        if (clickLockTicks > 0) return;
        if (!networkAvailable) return;

        // Scrollbar click
        if (scrollbarContains(mx, my)) {
            scrolling = true;
            updateScrollFromMouse(my);
            return;
        }

        // Grid slot click
        if (!gridSlotsContains(mx, my)) return;

        int slot = getSlotAt(mx, my);
        if (slot < 0) return;

        ItemStack carried = mc.player.containerMenu.getCarried();

        if (!carried.isEmpty()) {
            // Insert into RS
            if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                RSSidePanelNetworkHandler.sendInsert(slot, carried.copy());
                mc.player.containerMenu.setCarried(ItemStack.EMPTY);
            } else if (btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                ItemStack one = carried.copy();
                one.setCount(1);
                RSSidePanelNetworkHandler.sendInsert(slot, one);
                carried.shrink(1);
                if (carried.isEmpty()) mc.player.containerMenu.setCarried(ItemStack.EMPTY);
            }
            clickLockTicks = 8;
            return;
        }

        // Drag-distribute start (left button on empty hand)
        if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            gridDragging = true;
            gridDragButton = 0;
            gridDragSlots.clear();
            gridDragSlots.add(slot);
            gridDragCrossedSlots = false;
            return;
        }

        // Right click: extract half
        if (btn == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            RSSidePanelNetworkHandler.sendClick(slot,
                    RSSidePanelClickPacket.ACTION_EXTRACT_STACK, Screen.hasShiftDown());
            clickLockTicks = 8;
        }
    }

    @SuppressWarnings("resource")
    private static void onScreenMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Commit panel move
        if (movingPanel) {
            movingPanel = false;
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
            if (gridDragSlots.size() > 1 || gridDragCrossedSlots) {
                RSSidePanelNetworkHandler.sendDragDistribute(new ArrayList<>(gridDragSlots));
            } else if (!gridDragSlots.isEmpty()) {
                int s = gridDragSlots.iterator().next();
                RSSidePanelNetworkHandler.sendClick(s, RSSidePanelClickPacket.ACTION_EXTRACT_ONE, false);
            }
            gridDragging = false;
            gridDragSlots.clear();
            clickLockTicks = 8;
            event.setCanceled(true);
            return;
        }

        if (!panelVisible || panelHidden) return;
        if (interactiveContains(event.getMouseX(), event.getMouseY()) || clickLockTicks > 0)
            event.setCanceled(true);
    }

    @SuppressWarnings("resource")
    private static void onScreenMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!panelVisible || panelHidden) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        double mx = event.getMouseX(), my = event.getMouseY();

        if (movingPanel) {
            panelX = moveStartPanelX + ((int) mx - moveStartMouseX);
            panelY = moveStartPanelY + ((int) my - moveStartMouseY);
            event.setCanceled(true);
            return;
        }

        if (scrolling) {
            updateScrollFromMouse(my);
            event.setCanceled(true);
            return;
        }

        if (gridDragging) {
            int slot = getSlotAt(mx, my);
            if (slot >= 0) {
                if (!gridDragSlots.contains(slot)) gridDragCrossedSlots = true;
                gridDragSlots.add(slot);
            }
            event.setCanceled(true);
            return;
        }

        if (interactiveContains(mx, my)) event.setCanceled(true);
    }

    @SuppressWarnings("resource")
    private static void onScreenMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!panelVisible || panelHidden) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen == null) return;

        double mx = event.getMouseX(), my = event.getMouseY();
        if (clickLockTicks > 0) { event.setCanceled(true); return; }
        if (!interactiveContains(mx, my)) return;

        double delta = event.getScrollDelta();
        if (delta < 0) scrollRow++;
        else if (delta > 0) scrollRow--;
        clampScroll();
        event.setCanceled(true);
    }

    // ── mouse helpers ─────────────────────────────────────────────

    private static int getSlotAt(double mx, double my) {
        int gx = panelX + SIDE_COL_W + RS_GRID_LEFT;
        int gy = panelY + HEADER_H;
        int col = ((int) mx - gx) / SLOT_SIZE;
        int row = ((int) my - gy) / SLOT_SIZE;
        if (col < 0 || col >= COLUMNS || row < 0 || row >= visibleRows) return -1;

        List<ItemStack> display = getFilteredAndSortedList();
        int dIdx = (scrollRow + row) * COLUMNS + col;
        if (dIdx < 0 || dIdx >= display.size()) return -1;

        ItemStack stack = display.get(dIdx);
        int realIdx = cachedItems.indexOf(stack);
        return realIdx >= 0 ? realIdx : -1;
    }

    private static void updateScrollFromMouse(double my) {
        int totalRows = getTotalRows();
        int maxScroll = Math.max(0, totalRows - visibleRows);
        if (maxScroll <= 0) { scrollRow = 0; return; }

        int trackH = visibleRows * SLOT_SIZE;
        int sy = panelY + HEADER_H;
        double t = (my - sy) / Math.max(1, trackH);
        t = Mth.clamp(t, 0, 1);
        scrollRow = (int) Math.round(t * maxScroll);
        clampScroll();
    }

    private static void handleSideButtonClick(int idx) {
        switch (idx) {
            case 0: sortAsc = !sortAsc; break;
            case 1: sortMode = (sortMode + 1) % 4; break;
            case 2:
                boolean wasJei = searchMode >= 4;
                searchMode = (searchMode + 1) % 6;
                if (searchMode >= 4) {
                    try {
                        Class<?> cls = Class.forName(
                                "com.refinedmods.refinedstorage.integration.jei.RSJeiPlugin");
                        if (cls.getMethod("getRuntime").invoke(null) == null) searchMode = 0;
                    } catch (Exception e) { searchMode = 0; }
                }
                if (!wasJei && searchMode >= 4) { lastJeiFilterText = ""; pullJeiFilter(); }
                break;
            case 3: gridSize = (gridSize + 1) % 4; scrollRow = 0; break;
            case 4: viewType = (viewType + 1) % 3; scrollRow = 0; break;
        }
    }

    // ── tick ──────────────────────────────────────────────────────

    @SuppressWarnings("resource")
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (event.phase == TickEvent.Phase.START) return;
        if (!panelVisible) return;

        tickCounter++;
        if (clickLockTicks > 0) clickLockTicks--;

        int guiScale = (int) mc.getWindow().getGuiScale();
        int mx = (int) (mc.mouseHandler.xpos() / guiScale);
        int my = (int) (mc.mouseHandler.ypos() / guiScale);
        hoveredSideButton = -1;

        // No-Screen fallback: handle drag states (screen events won't fire)
        if (mc.screen == null) {
            if (scrolling) updateScrollFromMouse(my);
            if (movingPanel) {
                panelX = moveStartPanelX + (mx - moveStartMouseX);
                panelY = moveStartPanelY + (my - moveStartMouseY);
            }
        }

        if (searchMode >= 4 && tickCounter % 5 == 0) pullJeiFilter();
        if (panelHidden) return;

        // Periodic sync
        if (tickCounter % 10 == 0) {
            RSSidePanelNetworkHandler.sendRequestSync();
        }
    }

    // ── helpers ───────────────────────────────────────────────────

    private static int getTotalRows() {
        List<ItemStack> list = getFilteredAndSortedList();
        return (int) Math.ceil(list.size() / (double) COLUMNS);
    }

    private static void clampScroll() {
        int max = Math.max(0, getTotalRows() - visibleRows);
        if (scrollRow < 0) scrollRow = 0;
        if (scrollRow > max) scrollRow = max;
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

    @SuppressWarnings("resource")
    private static List<ItemStack> getFilteredAndSortedList() {
        List<ItemStack> list = new ArrayList<>(cachedItems);

        if (!searchText.isEmpty()) {
            String query = searchText.trim();
            if (query.startsWith("@")) {
                String mq = query.substring(1).toLowerCase();
                list.removeIf(stack -> {
                    var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    if (key == null) return true;
                    String modId = key.getNamespace().toLowerCase();
                    if (modId.contains(mq)) return false;
                    String modName = ModList.get().getModContainerById(key.getNamespace())
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
                String lower = query.toLowerCase();
                list.removeIf(stack -> {
                    if (stack.getHoverName().getString().toLowerCase().contains(lower)) return false;
                    var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    return key == null || !key.getPath().contains(lower);
                });
            }
        }

        if (viewType != 0) {
            list.removeIf(stack -> {
                int idx = cachedItems.indexOf(stack);
                boolean craftable = idx >= 0 && idx < cachedCraftableFlags.size()
                        && cachedCraftableFlags.get(idx);
                return viewType == 1 ? craftable : !craftable;
            });
        }

        switch (sortMode) {
            case 0 -> {
                if (sortAsc) list.sort(Comparator.comparingInt(ItemStack::getCount));
                else list.sort((a, b) -> Integer.compare(b.getCount(), a.getCount()));
            }
            case 1 -> {
                if (sortAsc) list.sort(Comparator.comparing(s -> s.getHoverName().getString().toLowerCase()));
                else list.sort(Comparator.comparing(
                        (ItemStack s) -> s.getHoverName().getString().toLowerCase()).reversed());
            }
            case 2 -> list.sort((a, b) -> {
                var ka = ForgeRegistries.ITEMS.getKey(a.getItem());
                var kb = ForgeRegistries.ITEMS.getKey(b.getItem());
                String ia = ka != null ? ka.toString() : "zzz:zzz";
                String ib = kb != null ? kb.toString() : "zzz:zzz";
                return sortAsc ? ia.compareToIgnoreCase(ib) : ib.compareToIgnoreCase(ia);
            });
            case 3 -> {
                int idx = sortAsc ? 1 : -1;
                list.sort((a, b) -> {
                    int ai = cachedItems.indexOf(a), bi = cachedItems.indexOf(b);
                    long ta = ai >= 0 && ai < cachedTimestamps.size() ? cachedTimestamps.get(ai) : 0L;
                    long tb = bi >= 0 && bi < cachedTimestamps.size() ? cachedTimestamps.get(bi) : 0L;
                    return idx * Long.compare(tb, ta);
                });
            }
        }
        return list;
    }
}
