package com.huanghuang.rsintegration.feature.unifiedgrid.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 统一网格资源筛选档位的客户端持久化。 */
final class UnifiedGridPreferences {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int SCHEMA = 1;
    private static final String FILE_VERSION = "version";
    private static final String FILTER = "filter";

    private UnifiedGridPreferences() {}

    static Path defaultPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve("rs_integration").resolve("unified_grid.json");
    }

    static int loadFilter(Path path, int filterLevels) {
        if (path == null || filterLevels <= 0 || !Files.isRegularFile(path)) return 0;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.get(FILE_VERSION).getAsInt() != SCHEMA) return 0;
            int filter = root.get(FILTER).getAsInt();
            return filter >= 0 && filter < filterLevels ? filter : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    static boolean saveFilter(Path path, int filter, int filterLevels) {
        if (path == null || filter < 0 || filter >= filterLevels || filterLevels <= 0) {
            return false;
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            JsonObject root = new JsonObject();
            root.addProperty(FILE_VERSION, SCHEMA);
            root.addProperty(FILTER, filter);
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (Exception ignored) {
                // 临时文件清理失败不应影响终端使用。
            }
        }
    }
}
