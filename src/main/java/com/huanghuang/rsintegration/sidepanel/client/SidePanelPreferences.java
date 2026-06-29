package com.huanghuang.rsintegration.sidepanel.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.huanghuang.rsintegration.RSIntegrationMod;

import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;

/**
 * Persistence for side-panel UI preferences.
 * Loads/saves sort mode, sort direction, search mode, grid size, and view type
 * to a JSON file in the config directory.
 */
public final class SidePanelPreferences {

    private static final Gson GSON = new Gson();

    private SidePanelPreferences() {}

    // ── Data record ───────────────────────────────────────────────

    /** Holds all persisted UI preference values. */
    public static final class Data {
        public int sortMode;
        public boolean sortAsc;
        public int searchMode;
        public int gridSize;
        public int viewType;

        public Data() {
            this.sortMode = 0;
            this.sortAsc = true;
            this.searchMode = 0;
            this.gridSize = 3;
            this.viewType = 0;
        }

        public Data(int sortMode, boolean sortAsc, int searchMode, int gridSize, int viewType) {
            this.sortMode = sortMode;
            this.sortAsc = sortAsc;
            this.searchMode = searchMode;
            this.gridSize = gridSize;
            this.viewType = viewType;
        }

        /** Clamp all values to their valid ranges. */
        public void clamp() {
            if (sortMode < 0 || sortMode > 3) sortMode = 0;
            if (searchMode < 0 || searchMode > 5) searchMode = 0;
            if (gridSize < 0 || gridSize > 3) gridSize = 3;
            if (viewType < 0 || viewType > 2) viewType = 0;
        }
    }

    // ── Load / Save ──────────────────────────────────────────────

    /**
     * Load preferences from the given JSON file.
     *
     * @param path path to the preferences file
     * @return loaded preferences data, or a default instance if the file does not exist
     */
    public static Data load(Path path) {
        Data data = new Data();
        try {
            var file = path.toFile();
            if (!file.exists()) return data;
            var json = GSON.fromJson(new FileReader(file), JsonObject.class);
            if (json == null) return data;
            if (json.has("sm")) data.sortMode = json.get("sm").getAsInt();
            if (json.has("sa")) data.sortAsc = json.get("sa").getAsBoolean();
            if (json.has("qm")) data.searchMode = json.get("qm").getAsInt();
            if (json.has("gs")) data.gridSize = json.get("gs").getAsInt();
            if (json.has("vt")) data.viewType = json.get("vt").getAsInt();
            data.clamp();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-SidePanel] Failed to load prefs", e);
        }
        return data;
    }

    /**
     * Save preferences to the given JSON file.
     *
     * @param path path to the preferences file
     * @param data current preferences to persist
     */
    public static void save(Path path, Data data) {
        try {
            var json = new JsonObject();
            json.addProperty("sm", data.sortMode);
            json.addProperty("sa", data.sortAsc);
            json.addProperty("qm", data.searchMode);
            json.addProperty("gs", data.gridSize);
            json.addProperty("vt", data.viewType);
            var parent = path.getParent().toFile();
            if (!parent.exists()) parent.mkdirs();
            try (var w = new FileWriter(path.toFile())) {
                GSON.toJson(json, w);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-SidePanel] Failed to save prefs", e);
        }
    }
}
