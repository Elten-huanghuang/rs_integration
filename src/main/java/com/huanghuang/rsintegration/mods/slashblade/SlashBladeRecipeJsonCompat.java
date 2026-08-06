package com.huanghuang.rsintegration.mods.slashblade;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Exact, data-backed repairs for recipes broken by known third-party overrides. */
public final class SlashBladeRecipeJsonCompat {
    private static final ResourceLocation RODAI_NETHERITE_SMITHING =
            new ResourceLocation("slashblade", "rodai_netherite_smithing");
    private static final String RODAI_RESOURCE =
            "/rs_integration/compat/slashblade/rodai_netherite_smithing.json";
    private static final JsonElement RODAI_RECIPE = load(RODAI_RESOURCE);

    private SlashBladeRecipeJsonCompat() {}

    public static Map<ResourceLocation, JsonElement> repairAll(
            Map<ResourceLocation, JsonElement> recipes) {
        Objects.requireNonNull(recipes, "recipes");
        Map<ResourceLocation, JsonElement> repaired = null;
        for (Map.Entry<ResourceLocation, JsonElement> entry : recipes.entrySet()) {
            JsonElement replacement = repair(entry.getKey(), entry.getValue());
            if (replacement == entry.getValue()) continue;
            if (repaired == null) repaired = new LinkedHashMap<>(recipes);
            repaired.put(entry.getKey(), replacement);
        }
        return repaired == null ? recipes : repaired;
    }

    public static JsonElement repair(ResourceLocation recipeId, JsonElement recipe) {
        Objects.requireNonNull(recipeId, "recipeId");
        if (RODAI_NETHERITE_SMITHING.equals(recipeId)
                && recipe != null && recipe.isJsonObject()
                && recipe.getAsJsonObject().entrySet().isEmpty()) {
            return RODAI_RECIPE.deepCopy();
        }
        return recipe;
    }

    private static JsonElement load(String resource) {
        try (InputStream stream = SlashBladeRecipeJsonCompat.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing compatibility recipe " + resource);
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load compatibility recipe " + resource, exception);
        }
    }
}
