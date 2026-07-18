package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.sidepanel.client.SidePanelInputHandler;
import com.huanghuang.rsintegration.sidepanel.client.SidePanelJeiBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts search-bar state, keyboard handling, and JEI filter
 * push/pull logic from {@link RSSidePanelClient}.
 */
final class SearchController {

    // ── Search state ──────────────────────────────────────────────
    static String searchText = "";
    static int searchMode;
    static final List<String> searchHistory = new ArrayList<>();
    static int historyIndex = -1;
    static String lastJeiFilterText = "";
    static EditBox searchWidget;
    static boolean searchChangedSinceBlur;
    static boolean searchFocused;

    private SearchController() {}

    // ── Lazy widget creation ──────────────────────────────────────

    static EditBox ensureWidget(Minecraft mc) {
        if (searchWidget == null) {
            searchWidget = new EditBox(mc.font, 0, 0, 82, 9,
                    Component.translatable("rsi.side_panel.search_hint"));
            searchWidget.setBordered(false);
            searchWidget.setTextColor(0xFFFFFFFF);
            searchWidget.setTextColorUneditable(0xFF777777);
            searchWidget.setValue(searchText);
            searchWidget.setResponder(t -> {
                searchText = t;
                searchChangedSinceBlur = true;
                RSSidePanelClient.displayDirty = true;
            });
        }
        return searchWidget;
    }

    private static boolean isSidePanelHostScreen(Minecraft mc) {
        if (mc.screen == null) return false;
        // The panel is an inventory/machine overlay. Keep the global keybind
        // out of chat, JEI, options/key-bindings and other non-container screens.
        return mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
    }

    // ── Key input (no screen open) ────────────────────────────────

    @SuppressWarnings("resource")
    static void onKeyInput(InputEvent.Key event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // The panel is an RS-grid overlay. Do not let its global keybind
        // fire while typing in another screen or changing controls.
        if (!isSidePanelHostScreen(mc)) return;
        if (mc.screen.getFocused() instanceof net.minecraft.client.gui.components.EditBox) return;
        if (RSSidePanelClient.KEY_TOGGLE_PANEL.isActiveAndMatches(
                com.mojang.blaze3d.platform.InputConstants.getKey(event.getKey(), event.getScanCode()))
                && event.getAction() == GLFW.GLFW_PRESS) {
            if (mc.screen == null) return; // only toggle when a screen is open
            RSSidePanelClient.panelVisible = !RSSidePanelClient.panelVisible;
            if (RSSidePanelClient.panelVisible) {
                RSSidePanelClient.panelScreenBound = true;
                if (!RSSidePanelClient.panelHidden) {
                    RSSidePanelNetworkHandler.sendRequestSync();
                    RSSidePanelClient.scrollRow = 0;
                }
            } else {
                RSSidePanelNetworkHandler.sendCloseRequest();
            }
            return;
        }
        if (!RSSidePanelClient.panelVisible || RSSidePanelClient.panelHidden) return;
        if (event.getAction() == GLFW.GLFW_PRESS) {
            int key = event.getKey();
            if ((key == GLFW.GLFW_KEY_U || key == GLFW.GLFW_KEY_R) && mc.screen == null) {
                RSSidePanelClient.showJeiForHoveredItem(key == GLFW.GLFW_KEY_U);
                return;
            }
        }
        if (!searchFocused) return;
        if (Minecraft.getInstance().screen != null) return;
        handleTextInput(event.getKey(), event.getScanCode(), event.getAction(), false);
    }

    // ── Key pressed (screen open) ─────────────────────────────────

    @SuppressWarnings("resource")
    static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!RSSidePanelClient.panelVisible || RSSidePanelClient.panelHidden) return;
        if (searchFocused) {
            event.setCanceled(true);
            handleTextInput(event.getKeyCode(), event.getScanCode(), GLFW.GLFW_PRESS, true);
            return;
        }
        if (RSSidePanelClient.anyPanelContains(RSSidePanelClient.lastMouseX, RSSidePanelClient.lastMouseY)) {
            int key = event.getKeyCode();
            if (key == GLFW.GLFW_KEY_U || key == GLFW.GLFW_KEY_R) {
                RSSidePanelClient.showJeiForHoveredItem(key == GLFW.GLFW_KEY_U);
                event.setCanceled(true);
                return;
            }
            boolean isModifier = event.getKeyCode() == GLFW.GLFW_KEY_LEFT_SHIFT
                    || event.getKeyCode() == GLFW.GLFW_KEY_RIGHT_SHIFT
                    || event.getKeyCode() == GLFW.GLFW_KEY_LEFT_CONTROL
                    || event.getKeyCode() == GLFW.GLFW_KEY_RIGHT_CONTROL
                    || event.getKeyCode() == GLFW.GLFW_KEY_LEFT_ALT
                    || event.getKeyCode() == GLFW.GLFW_KEY_RIGHT_ALT;
            if (isModifier) event.setCanceled(true);
        }
    }

    // ── Char typed (screen open) ──────────────────────────────────

    @SuppressWarnings("resource")
    static void onScreenCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (!RSSidePanelClient.panelVisible || RSSidePanelClient.panelHidden || !searchFocused) return;
        if (searchWidget == null) return;
        char cp = event.getCodePoint();
        if (cp >= 32 && cp != 127 && searchText.length() < 64) {
            searchWidget.charTyped(cp, Screen.hasControlDown() ? GLFW.GLFW_MOD_CONTROL : 0);
            pushJeiFilter();
            event.setCanceled(true);
        }
    }

    // ── Text input handling ───────────────────────────────────────

    static void handleTextInput(int key, int scanCode, int action, boolean screenOpen) {
        if (action != GLFW.GLFW_PRESS) return;
        if (searchWidget == null) return;

        if (key == GLFW.GLFW_KEY_ESCAPE) { onSearchBlur(); return; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if ((searchMode % 2) == 1 && RSSidePanelClient.networkAvailable) {
                DisplayListManager.ensureDisplayReady();
                if (!RSSidePanelClient.displayList.isEmpty()) {
                    var first = RSSidePanelClient.displayList.get(0);
                    RSSidePanelNetworkHandler.sendClick(first.getStack(),
                            RSSidePanelClickPacket.ACTION_EXTRACT_ONE, false, first.getId());
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
            String ch = SidePanelInputHandler.glfwKeyToChar(key, scanCode);
            if (ch != null) {
                for (int i = 0; i < ch.length(); i++)
                    searchWidget.charTyped(ch.charAt(i),
                            Screen.hasControlDown() ? GLFW.GLFW_MOD_CONTROL : 0);
                pushJeiFilter();
            }
        }
    }

    // ── Blur / history ────────────────────────────────────────────

    static void onSearchBlur() {
        if (searchWidget != null) searchText = searchWidget.getValue();
        if (searchChangedSinceBlur && !searchText.isEmpty()) pushSearchHistory();
        searchFocused = false;
    }

    static void pushSearchHistory() {
        String text = searchWidget != null ? searchWidget.getValue() : searchText;
        if (text.isEmpty()) return;
        searchHistory.remove(text);
        searchHistory.add(text);
        if (searchHistory.size() > 50) searchHistory.remove(0);
        historyIndex = -1;
    }

    // ── JEI filter bridge ─────────────────────────────────────────

    static void pushJeiFilter() {
        SidePanelJeiBridge.pushFilter(searchMode, searchText);
    }

    static void pullJeiFilter() {
        var result = SidePanelJeiBridge.pullFilter(searchMode, searchText, lastJeiFilterText);
        if (!result.changed()) return;
        lastJeiFilterText = result.newLastJeiFilterText;
        if (result.searchChanged) {
            searchText = result.newSearchText;
            if (searchWidget != null) searchWidget.setValue(result.newSearchText);
            historyIndex = -1;
            RSSidePanelClient.displayDirty = true;
        }
    }
}
