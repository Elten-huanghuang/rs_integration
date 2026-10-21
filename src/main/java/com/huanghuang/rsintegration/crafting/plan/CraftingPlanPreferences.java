package com.huanghuang.rsintegration.crafting.plan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.huanghuang.rsintegration.crafting.OutputDestination;

import javax.annotation.Nullable;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 客户端计划界面的输出目的地、配方选择和递归禁用偏好。 */
final class CraftingPlanPreferences {

    static final String NO_RECIPE = "rs_integration:no_recipe";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_ENTRIES = 4_096;

    private CraftingPlanPreferences() {}

    record Snapshot(OutputDestination outputDestination,
                    Map<String, String> recipeChoices,
                    Set<String> excludedRecipes) {
        Snapshot {
            outputDestination = outputDestination == null
                    ? OutputDestination.RS_NETWORK : outputDestination;
            recipeChoices = recipeChoices == null
                    ? Map.of() : Map.copyOf(recipeChoices);
            excludedRecipes = excludedRecipes == null
                    ? Set.of() : Set.copyOf(excludedRecipes);
        }

        static Snapshot empty() {
            return new Snapshot(OutputDestination.RS_NETWORK, Map.of(), Set.of());
        }
    }

    static synchronized Snapshot load(Path path) {
        if (path == null || !Files.isRegularFile(path)) return Snapshot.empty();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) return Snapshot.empty();
            OutputDestination destination = OutputDestination.RS_NETWORK;
            if (root.has("outputDestination")) {
                try {
                    destination = OutputDestination.valueOf(
                            root.get("outputDestination").getAsString());
                } catch (RuntimeException ignored) {
                    destination = OutputDestination.RS_NETWORK;
                }
            }
            return new Snapshot(destination,
                    readStringMap(root.getAsJsonObject("recipeChoices")),
                    readStringSet(root.getAsJsonArray("excludedRecipes")));
        } catch (Exception ignored) {
            return Snapshot.empty();
        }
    }

    static synchronized boolean save(Path path, Snapshot snapshot) {
        if (path == null || snapshot == null) return false;
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("outputDestination", snapshot.outputDestination().name());
            root.add("recipeChoices", writeStringMap(snapshot.recipeChoices()));
            root.add("excludedRecipes", writeStringSet(snapshot.excludedRecipes()));
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
        }
    }

    static OutputDestination loadOutputDestination(Path path) {
        return load(path).outputDestination();
    }

    static boolean saveOutputDestination(Path path, OutputDestination destination) {
        Snapshot current = load(path);
        return save(path, new Snapshot(destination, current.recipeChoices(),
                current.excludedRecipes()));
    }

    static boolean saveRecipeChoice(Path path, String key, @Nullable String recipeId) {
        if (key == null || key.isBlank()) return false;
        Snapshot current = load(path);
        Map<String, String> choices = new LinkedHashMap<>(current.recipeChoices());
        if (recipeId == null || recipeId.isBlank()) choices.remove(key);
        else choices.put(key, recipeId);
        return save(path, new Snapshot(current.outputDestination(), choices,
                current.excludedRecipes()));
    }

    static boolean setExcluded(Path path, String recipeId, boolean excluded) {
        if (recipeId == null || recipeId.isBlank()) return false;
        Snapshot current = load(path);
        Set<String> values = new LinkedHashSet<>(current.excludedRecipes());
        if (excluded) values.add(recipeId);
        else values.remove(recipeId);
        return save(path, new Snapshot(current.outputDestination(),
                current.recipeChoices(), values));
    }

    private static Map<String, String> readStringMap(@Nullable JsonObject object) {
        if (object == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (result.size() >= MAX_ENTRIES) break;
            if (entry.getValue().isJsonPrimitive()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return result;
    }

    private static Set<String> readStringSet(@Nullable JsonArray array) {
        if (array == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement element : array) {
            if (result.size() >= MAX_ENTRIES) break;
            if (element.isJsonPrimitive()) result.add(element.getAsString());
        }
        return result;
    }

    private static JsonObject writeStringMap(Map<String, String> values) {
        JsonObject result = new JsonObject();
        values.entrySet().stream().limit(MAX_ENTRIES)
                .forEach(entry -> result.addProperty(entry.getKey(), entry.getValue()));
        return result;
    }

    private static JsonArray writeStringSet(Set<String> values) {
        JsonArray result = new JsonArray();
        values.stream().limit(MAX_ENTRIES).forEach(result::add);
        return result;
    }
}
