package com.huanghuang.rsintegration.feature.foodorder.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

/** 收藏按物品 ID 保存在客户端，缺失模组的 ID 也保留，重进游戏不会丢失。 */
final class FoodOrderFavorites {
    private static final int SCHEMA = 1;
    private static final int MAX_ENTRIES = 5000;
    private static final long MAX_FILE_BYTES = 1_048_576L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Set<ResourceLocation> items = new LinkedHashSet<>();

    boolean contains(ResourceLocation item) {
        return item != null && items.contains(item);
    }

    boolean toggle(ResourceLocation item) {
        if (item == null) {
            return false;
        }
        if (!items.remove(item)) {
            if (items.size() >= MAX_ENTRIES) {
                return false;
            }
            items.add(item);
        }
        return true;
    }

    boolean load(Path path) {
        if (!Files.isRegularFile(path)) {
            return true;
        }
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                return false;
            }
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (!root.has("schema") || root.get("schema").getAsInt() != SCHEMA
                    || !root.has("items") || !root.get("items").isJsonArray()) {
                return false;
            }
            Set<ResourceLocation> loaded = new LinkedHashSet<>();
            for (JsonElement element : root.getAsJsonArray("items")) {
                if (loaded.size() >= MAX_ENTRIES) {
                    break;
                }
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    continue;
                }
                ResourceLocation item = ResourceLocation.tryParse(element.getAsString());
                if (item != null) {
                    loaded.add(item);
                }
            }
            items.clear();
            items.addAll(loaded);
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    void save(Path path) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        JsonArray array = new JsonArray();
        items.forEach(item -> array.add(item.toString()));
        root.add("items", array);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
