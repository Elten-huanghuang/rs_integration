package com.huanghuang.rsintegration.mods.pmmo;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.LogicalSide;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reflective server-side projection of PMMO's live ITEM_LOADER salvage data. */
public final class PmmoSalvageCatalog {
    private static final String CORE_CLASS = "harmonised.pmmo.core.Core";
    private static final String CONFIG_CLASS = "harmonised.pmmo.config.Config";
    private static volatile Map<ResourceLocation, PmmoSalvageRecipeWrapper> recipes = Map.of();

    private PmmoSalvageCatalog() {}

    public static synchronized List<PmmoSalvageRecipeWrapper> refresh() {
        Map<ResourceLocation, PmmoSalvageRecipeWrapper> decoded = new LinkedHashMap<>();
        try {
            Class<?> coreClass = Class.forName(CORE_CLASS);
            Object core = coreClass.getMethod("get", LogicalSide.class)
                    .invoke(null, LogicalSide.SERVER);
            Object loader = coreClass.getMethod("getLoader").invoke(core);
            Field itemLoaderField = loader.getClass().getField("ITEM_LOADER");
            Object itemLoader = itemLoaderField.get(loader);
            Object raw = itemLoader.getClass().getMethod("getData").invoke(itemLoader);
            if (raw instanceof Map<?, ?> itemData) {
                for (PmmoSalvageDefinition definition : decode(itemData)) {
                    for (PmmoSalvageDefinition.Output output : definition.outputs()) {
                        PmmoSalvageRecipeWrapper recipe =
                                new PmmoSalvageRecipeWrapper(definition, output);
                        decoded.put(recipe.getId(), recipe);
                    }
                }
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            RSIntegrationMod.LOGGER.warn("[RSI-PMMO] Unable to read server salvage data", exception);
        }
        recipes = Map.copyOf(decoded);
        return sortedRecipes(recipes);
    }

    public static List<PmmoSalvageRecipeWrapper> allRecipes() {
        Map<ResourceLocation, PmmoSalvageRecipeWrapper> snapshot = recipes;
        return snapshot.isEmpty() ? refresh() : sortedRecipes(snapshot);
    }

    @Nullable
    public static PmmoSalvageRecipeWrapper byId(ResourceLocation id) {
        PmmoSalvageRecipeWrapper recipe = recipes.get(id);
        if (recipe != null) return recipe;
        refresh();
        return recipes.get(id);
    }

    @Nullable
    public static ResourceLocation salvageBlockId() {
        try {
            Class<?> configClass = Class.forName(CONFIG_CLASS);
            Object configValue = configClass.getField("SALVAGE_BLOCK").get(null);
            Object rawId = configValue.getClass().getMethod("get").invoke(configValue);
            return rawId instanceof String value ? ResourceLocation.tryParse(value) : null;
        } catch (ReflectiveOperationException | LinkageError exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-PMMO] Salvage block config unavailable", exception);
            return null;
        }
    }

    static List<PmmoSalvageDefinition> decode(Map<?, ?> itemData) {
        List<PmmoSalvageDefinition> definitions = new ArrayList<>();
        for (Map.Entry<?, ?> inputEntry : itemData.entrySet()) {
            if (!(inputEntry.getKey() instanceof ResourceLocation inputId)) continue;
            Map<?, ?> salvage = invokeMap(inputEntry.getValue(), "salvage");
            List<PmmoSalvageDefinition.Output> outputs = new ArrayList<>();
            for (Map.Entry<?, ?> outputEntry : salvage.entrySet()) {
                if (!(outputEntry.getKey() instanceof ResourceLocation outputId)) continue;
                Object data = outputEntry.getValue();
                int salvageMax = invokeNumber(data, "salvageMax", 1).intValue();
                if (salvageMax <= 0) continue;
                outputs.add(new PmmoSalvageDefinition.Output(
                        outputId, salvageMax,
                        invokeNumber(data, "baseChance", 0.0D).doubleValue(),
                        invokeNumber(data, "maxChance", 1.0D).doubleValue(),
                        numberMap(data, "chancePerLevel", Number::doubleValue),
                        numberMap(data, "levelReq", Number::intValue),
                        numberMap(data, "xpAward", Number::longValue)));
            }
            if (!outputs.isEmpty()) {
                definitions.add(new PmmoSalvageDefinition(inputId, outputs));
            }
        }
        definitions.sort(Comparator.comparing(definition -> definition.inputId().toString()));
        return List.copyOf(definitions);
    }

    private static List<PmmoSalvageRecipeWrapper> sortedRecipes(
            Map<ResourceLocation, PmmoSalvageRecipeWrapper> source) {
        return source.values().stream()
                .sorted(Comparator.comparing(recipe -> recipe.getId().toString()))
                .toList();
    }

    private static Map<?, ?> invokeMap(Object target, String methodName) {
        if (target == null) return Map.of();
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof Map<?, ?> map ? map : Map.of();
        } catch (ReflectiveOperationException exception) {
            return Map.of();
        }
    }

    private static Number invokeNumber(Object target, String methodName, Number fallback) {
        if (target == null) return fallback;
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof Number number ? number : fallback;
        } catch (ReflectiveOperationException exception) {
            return fallback;
        }
    }

    private interface NumberConverter<N extends Number> {
        N convert(Number number);
    }

    private static <N extends Number> Map<String, N> numberMap(
            Object target, String methodName, NumberConverter<N> converter) {
        Map<String, N> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : invokeMap(target, methodName).entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof Number number) {
                result.put(key, converter.convert(number));
            }
        }
        return result;
    }
}
