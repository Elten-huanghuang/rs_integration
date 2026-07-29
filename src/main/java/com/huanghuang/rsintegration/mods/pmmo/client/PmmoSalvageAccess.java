package com.huanghuang.rsintegration.mods.pmmo.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.LogicalSide;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reflective access keeps PMMO a genuinely optional runtime dependency. */
public final class PmmoSalvageAccess {
    private static final String CORE_CLASS = "harmonised.pmmo.core.Core";
    private static final String CONFIG_CLASS = "harmonised.pmmo.config.Config";

    private PmmoSalvageAccess() {}

    public static List<PmmoSalvageRecipe> recipes() {
        try {
            Class<?> coreClass = Class.forName(CORE_CLASS);
            Object core = coreClass.getMethod("get", LogicalSide.class)
                    .invoke(null, LogicalSide.CLIENT);
            Object loader = coreClass.getMethod("getLoader").invoke(core);
            Field itemLoaderField = loader.getClass().getField("ITEM_LOADER");
            Object itemLoader = itemLoaderField.get(loader);
            Object rawData = itemLoader.getClass().getMethod("getData").invoke(itemLoader);
            return rawData instanceof Map<?, ?> data ? decodeItemData(data) : List.of();
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return List.of();
        }
    }

    public static ItemStack salvageBlock() {
        ResourceLocation id = salvageBlockId();
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.BLOCK.get(id).asItem();
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    public static ResourceLocation salvageBlockId() {
        try {
            Class<?> configClass = Class.forName(CONFIG_CLASS);
            Object configValue = configClass.getField("SALVAGE_BLOCK").get(null);
            Object rawId = configValue.getClass().getMethod("get").invoke(configValue);
            ResourceLocation id = rawId instanceof String value
                    ? ResourceLocation.tryParse(value)
                    : null;
            return id != null && BuiltInRegistries.BLOCK.containsKey(id) ? id : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    static List<PmmoSalvageRecipe> decodeItemData(Map<?, ?> itemData) {
        List<PmmoSalvageRecipe> recipes = new ArrayList<>();
        for (Map.Entry<?, ?> inputEntry : itemData.entrySet()) {
            if (!(inputEntry.getKey() instanceof ResourceLocation inputId)) continue;
            Item inputItem = BuiltInRegistries.ITEM.get(inputId);
            if (inputItem == Items.AIR) continue;

            Map<?, ?> salvage = invokeMap(inputEntry.getValue(), "salvage");
            for (Map.Entry<?, ?> outputEntry : salvage.entrySet()) {
                if (!(outputEntry.getKey() instanceof ResourceLocation outputId)) continue;
                Item outputItem = BuiltInRegistries.ITEM.get(outputId);
                if (outputItem == Items.AIR) continue;

                Object data = outputEntry.getValue();
                int salvageMax = invokeNumber(data, "salvageMax", 1).intValue();
                if (salvageMax <= 0) continue;
                double baseChance = invokeNumber(data, "baseChance", 0.0D).doubleValue();
                double maxChance = invokeNumber(data, "maxChance", 1.0D).doubleValue();
                recipes.add(new PmmoSalvageRecipe(
                        inputId,
                        outputId,
                        new ItemStack(inputItem),
                        new ItemStack(outputItem),
                        salvageMax,
                        baseChance,
                        maxChance,
                        numberMap(data, "chancePerLevel", Double.class),
                        numberMap(data, "levelReq", Integer.class),
                        numberMap(data, "xpAward", Long.class)));
            }
        }
        recipes.sort(Comparator.comparing((PmmoSalvageRecipe recipe) -> recipe.inputId().toString())
                .thenComparing(recipe -> recipe.outputId().toString()));
        return List.copyOf(recipes);
    }

    private static Map<?, ?> invokeMap(Object target, String methodName) {
        if (target == null) return Map.of();
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof Map<?, ?> map ? map : Map.of();
        } catch (ReflectiveOperationException ignored) {
            return Map.of();
        }
    }

    private static Number invokeNumber(Object target, String methodName, Number fallback) {
        if (target == null) return fallback;
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof Number number ? number : fallback;
        } catch (ReflectiveOperationException ignored) {
            return fallback;
        }
    }

    private static <N extends Number> Map<String, N> numberMap(
            Object target, String methodName, Class<N> numberType) {
        Map<?, ?> source = invokeMap(target, methodName);
        Map<String, N> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !numberType.isInstance(entry.getValue())) continue;
            result.put(key, numberType.cast(entry.getValue()));
        }
        return result;
    }
}
