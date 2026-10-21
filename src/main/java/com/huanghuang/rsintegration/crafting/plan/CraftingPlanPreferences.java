package com.huanghuang.rsintegration.crafting.plan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

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

/** 客户端计划界面的输出目的地、配方选择、材料选择和递归禁用偏好。 */
final class CraftingPlanPreferences {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_ENTRIES = 4_096;

    private CraftingPlanPreferences() {}

    record Snapshot(OutputDestination outputDestination,
                    Map<String, RecipeChoice> recipeChoices,
                    Map<String, ItemStack> ingredientChoices,
                    Set<String> excludedRecipes) {
        Snapshot {
            outputDestination = outputDestination == null
                    ? OutputDestination.RS_NETWORK : outputDestination;
            recipeChoices = recipeChoices == null
                    ? Map.of() : Map.copyOf(recipeChoices);
            ingredientChoices = immutableItems(ingredientChoices);
            excludedRecipes = excludedRecipes == null
                    ? Set.of() : Set.copyOf(excludedRecipes);
        }

        static Snapshot empty() {
            return new Snapshot(OutputDestination.RS_NETWORK, Map.of(), Map.of(), Set.of());
        }

        PlanningPreferences toPlanningPreferences() {
            Set<ResourceLocation> excluded = new LinkedHashSet<>();
            for (String value : excludedRecipes) {
                ResourceLocation id = ResourceLocation.tryParse(value);
                if (id != null) excluded.add(id);
            }
            return new PlanningPreferences(recipeChoices, ingredientChoices, excluded);
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
                    readRecipeChoices(root.getAsJsonObject("recipeChoices")),
                    readIngredientChoices(root.getAsJsonObject("ingredientChoices")),
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
            root.add("recipeChoices", writeRecipeChoices(snapshot.recipeChoices()));
            root.add("ingredientChoices", writeIngredientChoices(snapshot.ingredientChoices()));
            root.add("excludedRecipes", writeStringSet(snapshot.excludedRecipes()));
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
                writer.flush();
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
                current.ingredientChoices(), current.excludedRecipes()));
    }

    static boolean saveRecipeChoice(Path path, String key, @Nullable RecipeChoice choice) {
        if (key == null || key.isBlank()) return false;
        Snapshot current = load(path);
        Map<String, RecipeChoice> choices = new LinkedHashMap<>(current.recipeChoices());
        if (choice == null) choices.remove(key);
        else choices.put(key, choice);
        return save(path, new Snapshot(current.outputDestination(), choices,
                current.ingredientChoices(), current.excludedRecipes()));
    }

    static boolean saveRecipeChoice(Path path, String key, @Nullable String recipeId) {
        return saveRecipeChoice(path, key, RecipeChoice.parse(recipeId));
    }

    static boolean saveIngredientChoice(Path path, String key, @Nullable ItemStack stack) {
        if (key == null || key.isBlank()) return false;
        Snapshot current = load(path);
        Map<String, ItemStack> choices = new LinkedHashMap<>(current.ingredientChoices());
        if (stack == null || stack.isEmpty()) choices.remove(key);
        else choices.put(key, stack.copyWithCount(1));
        return save(path, new Snapshot(current.outputDestination(), current.recipeChoices(),
                choices, current.excludedRecipes()));
    }

    static boolean setExcluded(Path path, String recipeId, boolean excluded) {
        if (recipeId == null || recipeId.isBlank()) return false;
        Snapshot current = load(path);
        Set<String> values = new LinkedHashSet<>(current.excludedRecipes());
        if (excluded) values.add(recipeId);
        else values.remove(recipeId);
        boolean saved = save(path, new Snapshot(current.outputDestination(),
                current.recipeChoices(), current.ingredientChoices(), values));
        if (saved) ClientRecipeExclusions.invalidate();
        return saved;
    }

    private static Map<String, RecipeChoice> readRecipeChoices(@Nullable JsonObject object) {
        if (object == null) return Map.of();
        Map<String, RecipeChoice> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (result.size() >= MAX_ENTRIES) break;
            if (!entry.getValue().isJsonPrimitive()
                    || ResourceLocation.tryParse(entry.getKey()) == null) continue;
            RecipeChoice choice = RecipeChoice.parse(entry.getValue().getAsString());
            if (choice != null) result.put(entry.getKey(), choice);
        }
        return result;
    }

    private static Map<String, ItemStack> readIngredientChoices(@Nullable JsonObject object) {
        if (object == null) return Map.of();
        Map<String, ItemStack> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (result.size() >= MAX_ENTRIES) break;
            if (!entry.getValue().isJsonObject()) continue;
            ItemStack stack = readIngredientChoice(entry.getValue().getAsJsonObject());
            if (stack != null && !stack.isEmpty()) result.put(entry.getKey(), stack);
        }
        return result;
    }

    @Nullable
    private static ItemStack readIngredientChoice(JsonObject object) {
        JsonElement itemValue = object.get("item");
        if (itemValue == null || !itemValue.isJsonPrimitive()) return null;
        ResourceLocation itemId = ResourceLocation.tryParse(itemValue.getAsString());
        Item item = itemId == null ? null : BuiltInRegistries.ITEM.get(itemId);
        if (item == null) return null;
        ItemStack stack = new ItemStack(item);
        JsonElement nbtValue = object.get("nbt");
        if (nbtValue != null && nbtValue.isJsonPrimitive()) {
            String nbt = nbtValue.getAsString();
            if (!nbt.isBlank()) {
                try {
                    stack.setTag(TagParser.parseTag(nbt));
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return stack;
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

    private static JsonObject writeRecipeChoices(Map<String, RecipeChoice> values) {
        JsonObject result = new JsonObject();
        values.entrySet().stream().limit(MAX_ENTRIES)
                .forEach(entry -> result.addProperty(
                        entry.getKey(), entry.getValue().serialize()));
        return result;
    }

    private static JsonObject writeIngredientChoices(Map<String, ItemStack> values) {
        JsonObject result = new JsonObject();
        values.entrySet().stream().limit(MAX_ENTRIES).forEach(entry -> {
            ItemStack stack = entry.getValue();
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId == null) return;
            JsonObject value = new JsonObject();
            value.addProperty("item", itemId.toString());
            value.addProperty("nbt", stack.getTag() == null ? "" : stack.getTag().toString());
            result.add(entry.getKey(), value);
        });
        return result;
    }

    private static JsonArray writeStringSet(Set<String> values) {
        JsonArray result = new JsonArray();
        values.stream().limit(MAX_ENTRIES).forEach(result::add);
        return result;
    }

    private static Map<String, ItemStack> immutableItems(
            @Nullable Map<String, ItemStack> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, ItemStack> copy = new LinkedHashMap<>();
        values.forEach((key, stack) -> {
            if (key != null && !key.isBlank() && stack != null && !stack.isEmpty()) {
                copy.put(key, stack.copyWithCount(1));
            }
        });
        return Map.copyOf(copy);
    }
}
