package com.huanghuang.rsintegration.crafting.plan;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.huanghuang.rsintegration.crafting.OutputDestination;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persists client-only crafting-plan UI preferences. */
final class CraftingPlanPreferences {

    private static final Gson GSON = new Gson();
    private static final String OUTPUT_DESTINATION = "outputDestination";

    private CraftingPlanPreferences() {}

    static OutputDestination loadOutputDestination(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return OutputDestination.RS_NETWORK;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json == null || !json.has(OUTPUT_DESTINATION)) {
                return OutputDestination.RS_NETWORK;
            }
            return OutputDestination.valueOf(json.get(OUTPUT_DESTINATION).getAsString());
        } catch (Exception ignored) {
            return OutputDestination.RS_NETWORK;
        }
    }

    static boolean saveOutputDestination(Path path, OutputDestination destination) {
        if (path == null) return false;
        OutputDestination value = destination == null
                ? OutputDestination.RS_NETWORK : destination;
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            JsonObject json = new JsonObject();
            json.addProperty(OUTPUT_DESTINATION, value.name());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
