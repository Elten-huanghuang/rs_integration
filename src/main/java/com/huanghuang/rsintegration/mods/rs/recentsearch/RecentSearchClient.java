package com.huanghuang.rsintegration.mods.rs.recentsearch;
import java.lang.reflect.Field;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.machine.MachineHub;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.widget.SearchWidget;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class RecentSearchClient {
    private static RecentSearchHistory history;
    private static Path loadedPath;
    private static int loadedCapacity;
    private static int selectedIndex = -1;
    private static String observedQuery = "";
    private static boolean initialized;
    private static boolean saveErrorReported;
    private static boolean storageWritable = true;
    private static SearchWidget renderingSearchField;

    private RecentSearchClient() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,
                RecentSearchClient::onRender);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH,
                RecentSearchClient::onMousePressed);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH,
                RecentSearchClient::onKeyPressed);
        MinecraftForge.EVENT_BUS.addListener(RecentSearchClient::onCharacterTyped);
        MinecraftForge.EVENT_BUS.addListener(RecentSearchClient::onScreenClosing);
        MinecraftForge.EVENT_BUS.addListener(RecentSearchClient::onLogout);
    }

    private static void onRender(ScreenEvent.Render.Post event) {
        GridScreen screen = gridScreen(event.getScreen());
        if (!canUse(screen) || !ensureLoaded()) return;
        SearchWidget field = screen.getSearchField();
        String query = field.getValue();
        if (!query.equals(observedQuery)) {
            observedQuery = query;
            selectedIndex = -1;
        }
        if (!field.isFocused()) return;

        RecentSearchOverlay.Layout layout = layout(screen, field);
        if (selectedIndex >= layout.entries().size()) selectedIndex = -1;
        renderingSearchField = field;
        try {
            RecentSearchOverlay.render(event.getGuiGraphics(), Minecraft.getInstance().font,
                    field, layout, selectedIndex, event.getMouseX(), event.getMouseY());
        } finally {
            renderingSearchField = null;
        }
    }

    private static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        GridScreen screen = gridScreen(event.getScreen());
        if (!canUse(screen) || !ensureLoaded()) return;
        SearchWidget field = screen.getSearchField();
        if (!field.isFocused()) return;

        RecentSearchOverlay.Layout layout = layout(screen, field);
        RecentSearchOverlay.Hit hit = RecentSearchOverlay.hitTest(
                field, layout, event.getMouseX(), event.getMouseY());
        if (hit != null) {
            handleHit(screen, field, layout, hit);
            event.setCanceled(true);
            return;
        }
        if (layout.containsPanel(event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
            return;
        }

        if (field.isMouseOver(event.getMouseX(), event.getMouseY())) {
            if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) commit(field.getValue());
        } else {
            commit(field.getValue());
            field.setFocused(false);
            if (screen.getFocused() == field) screen.setFocused(null);
            selectedIndex = -1;
        }
    }

    private static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        GridScreen screen = gridScreen(event.getScreen());
        if (!canUse(screen) || !ensureLoaded()) return;
        SearchWidget field = screen.getSearchField();
        if (!field.isFocused()) return;

        RecentSearchOverlay.Layout layout = layout(screen, field);
        if (event.getKeyCode() == GLFW.GLFW_KEY_UP && !layout.entries().isEmpty()) {
            selectedIndex = selectedIndex <= 0
                    ? layout.entries().size() - 1 : selectedIndex - 1;
            event.setCanceled(true);
            return;
        }
        if (event.getKeyCode() == GLFW.GLFW_KEY_DOWN && !layout.entries().isEmpty()) {
            selectedIndex = selectedIndex < 0 || selectedIndex >= layout.entries().size() - 1
                    ? 0 : selectedIndex + 1;
            event.setCanceled(true);
            return;
        }
        if ((event.getKeyCode() == GLFW.GLFW_KEY_ENTER
                || event.getKeyCode() == GLFW.GLFW_KEY_KP_ENTER)
                && selectedIndex >= 0 && selectedIndex < layout.entries().size()) {
            apply(screen, field, layout.entries().get(selectedIndex).query());
            event.setCanceled(true);
            return;
        }
        if (event.getKeyCode() == GLFW.GLFW_KEY_ENTER
                || event.getKeyCode() == GLFW.GLFW_KEY_KP_ENTER
                || event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE) {
            commit(field.getValue());
            selectedIndex = -1;
            return;
        }
        selectedIndex = -1;
    }

    private static void onCharacterTyped(ScreenEvent.CharacterTyped.Pre event) {
        GridScreen screen = gridScreen(event.getScreen());
        if (screen != null && screen.getSearchField().isFocused()) selectedIndex = -1;
    }

    private static void onScreenClosing(ScreenEvent.Closing event) {
        GridScreen screen = gridScreen(event.getScreen());
        if (screen != null && RSIntegrationConfig.RS_RECENT_SEARCH_ENABLED.get()
                && ensureLoaded()) {
            commit(screen.getSearchField().getValue());
        }
        selectedIndex = -1;
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        history = null;
        loadedPath = null;
        loadedCapacity = 0;
        selectedIndex = -1;
        observedQuery = "";
        saveErrorReported = false;
        storageWritable = true;
    }

    private static void handleHit(GridScreen screen, SearchWidget field,
                                  RecentSearchOverlay.Layout layout,
                                  RecentSearchOverlay.Hit hit) {
        switch (hit.action()) {
            case APPLY -> apply(screen, field, layout.entries().get(hit.entryIndex()).query());
            case TOGGLE_FAVORITE -> {
                if (history.toggleFavorite(layout.entries().get(hit.entryIndex()).query())) save();
                selectedIndex = -1;
                retainFocus(screen, field);
            }
            case DELETE -> {
                if (history.remove(layout.entries().get(hit.entryIndex()).query())) save();
                selectedIndex = -1;
                retainFocus(screen, field);
            }
            case TOGGLE_CURRENT_FAVORITE -> {
                if (history.toggleFavorite(field.getValue())) save();
                selectedIndex = -1;
                retainFocus(screen, field);
            }
            case CLEAR_ALL -> {
                if (history.clear()) save();
                selectedIndex = -1;
                retainFocus(screen, field);
            }
        }
    }

    private static void apply(GridScreen screen, SearchWidget field, String query) {
        if (history.record(query)) save();
        field.setValue(query);
        field.moveCursorToEnd();
        field.setFocused(false);
        screen.setFocused(null);
        selectedIndex = -1;
        observedQuery = query;
    }

    private static void retainFocus(GridScreen screen, SearchWidget field) {
        field.setFocused(true);
        screen.setFocused(field);
    }

    private static void commit(String query) {
        if (history != null && history.record(query)) save();
    }

    public static void recordNativeSearch(SearchWidget field) {
        Minecraft minecraft = Minecraft.getInstance();
        GridScreen screen = gridScreen(minecraft.screen);
        if (!canUse(screen) || screen.getSearchField() != field || !ensureLoaded()) return;
        commit(field.getValue());
    }

    public static boolean shouldSuppressGridTooltip(GridScreen screen, int mouseX, int mouseY) {
        if (!canUse(screen) || !ensureLoaded()) return false;
        SearchWidget field = screen.getSearchField();
        if (!field.isFocused()) return false;
        return RecentSearchOverlay.containsOverlay(field, layout(screen, field), mouseX, mouseY);
    }

    private static RecentSearchOverlay.Layout layout(GridScreen screen, SearchWidget field) {
        boolean favorites = RSIntegrationConfig.RS_RECENT_SEARCH_FAVORITES_ENABLED.get();
        List<RecentSearchEntry> entries = favorites
                ? history.favoriteEntries(
                        RSIntegrationConfig.RS_RECENT_SEARCH_MAX_VISIBLE_ENTRIES.get())
                : List.of();
        return RecentSearchOverlay.layout(screen, field, entries, favorites,
                RSIntegrationConfig.RS_RECENT_SEARCH_DELETE_BUTTONS_ENABLED.get());
    }

    private static boolean ensureLoaded() {
        Minecraft minecraft = Minecraft.getInstance();
        Path path = RecentSearchScope.historyPath(minecraft);
        if (path == null) return false;
        int capacity = RSIntegrationConfig.RS_RECENT_SEARCH_MAX_STORED_ENTRIES.get();
        if (path.equals(loadedPath) && history != null && capacity == loadedCapacity) return true;

        RecentSearchHistory loadedHistory = new RecentSearchHistory(capacity);
        RecentSearchStore.LoadResult result = RecentSearchStore.load(path, capacity);
        loadedHistory.replaceAll(result.entries());
        history = loadedHistory;
        loadedPath = path;
        loadedCapacity = capacity;
        saveErrorReported = false;
        storageWritable = result.accepted() || result.corrupt();
        selectedIndex = -1;
        if (result.corrupt()) {
            RSIntegrationMod.LOGGER.warn("[RSI-RecentSearch] Ignoring corrupt history file {}", path);
        } else if (!result.accepted()) {
            RSIntegrationMod.LOGGER.warn("[RSI-RecentSearch] Ignoring unsupported history file {}", path);
        }
        return true;
    }

    private static void save() {
        if (history == null || loadedPath == null || !storageWritable) return;
        try {
            RecentSearchStore.save(loadedPath, history.entries());
            saveErrorReported = false;
        } catch (IOException exception) {
            if (!saveErrorReported) {
                saveErrorReported = true;
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-RecentSearch] Failed to save search history {}", loadedPath, exception);
            }
        }
    }

    private static boolean canUse(@Nullable GridScreen screen) {
        return screen != null && RSIntegrationConfig.RS_RECENT_SEARCH_ENABLED.get()
                && !MachineHub.isVisible();
    }

    @Nullable
    private static GridScreen gridScreen(Object screen) {
        return screen instanceof GridScreen grid ? grid : null;
    }

    static boolean isCurrentQueryFavorite() {
        return history != null && renderingSearchField != null
                && history.isFavorite(renderingSearchField.getValue());
    }

}
