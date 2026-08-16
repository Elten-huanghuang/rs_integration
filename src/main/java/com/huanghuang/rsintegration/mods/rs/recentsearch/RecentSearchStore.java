package com.huanghuang.rsintegration.mods.rs.recentsearch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

final class RecentSearchStore {
    static final int SCHEMA = 1;
    static final long MAX_FILE_BYTES = 4_194_304L;
    static final int MAX_QUERY_CHARS = 512;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    record LoadResult(List<RecentSearchEntry> entries, boolean accepted, boolean corrupt) {}

    private RecentSearchStore() {}

    static LoadResult load(Path path, int maxEntries) {
        if (!Files.isRegularFile(path)) return new LoadResult(List.of(), true, false);
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                return new LoadResult(List.of(), false, false);
            }
            JsonElement rootElement = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8));
            if (!rootElement.isJsonObject()) {
                return new LoadResult(List.of(), false, true);
            }
            JsonObject root = rootElement.getAsJsonObject();
            if (!root.has("schema") || root.get("schema").getAsInt() != SCHEMA) {
                return new LoadResult(List.of(), false, false);
            }
            JsonArray array = root.getAsJsonArray("entries");
            if (array == null) return new LoadResult(List.of(), true, false);

            List<RecentSearchEntry> entries = new ArrayList<>(Math.min(maxEntries, array.size()));
            for (JsonElement element : array) {
                if (entries.size() == maxEntries) break;
                RecentSearchEntry entry = parseEntry(element);
                if (entry != null) entries.add(entry);
            }
            return new LoadResult(List.copyOf(entries), true, false);
        } catch (IOException | RuntimeException ignored) {
            return new LoadResult(List.of(), false, true);
        }
    }

    static void save(Path path, List<RecentSearchEntry> entries) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        JsonArray array = new JsonArray();
        for (RecentSearchEntry entry : entries) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("query", entry.query());
            encoded.addProperty("favorite", entry.favorite());
            array.add(encoded);
        }
        root.add("entries", array);

        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            String json = GSON.toJson(root);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
                throw new IOException("Recent search history exceeds the file size limit");
            }
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            moveAtomically(temporary, path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static RecentSearchEntry parseEntry(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        JsonElement queryElement = object.get("query");
        if (queryElement == null || !queryElement.isJsonPrimitive()
                || !queryElement.getAsJsonPrimitive().isString()) {
            return null;
        }
        String query = queryElement.getAsString().trim();
        if (query.isEmpty() || query.length() > MAX_QUERY_CHARS) return null;
        boolean favorite = object.has("favorite") && object.get("favorite").getAsBoolean();
        return new RecentSearchEntry(query, favorite);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
