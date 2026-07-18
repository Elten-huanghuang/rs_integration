package com.huanghuang.rsintegration.mods.distantworlds;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit Firon recipe table for Distant Worlds 1.1.0 Beta. */
public final class LithumAltarRecipeResolver {
    public static final String MOD_ID = "distant_worlds";
    public static final String TYPE_ID = "distant_worlds_lithum_altar";
    private static final Map<ResourceLocation, LithumAltarRecipeDefinition> DEFINITIONS = new LinkedHashMap<>();

    static {
        add("firon_sword", "minecraft:netherite_sword", "distant_worlds:firon_sword");
        add("firon_pickaxe", "minecraft:netherite_pickaxe", "distant_worlds:firon_pickaxe");
        add("firon_axe", "minecraft:netherite_axe", "distant_worlds:firon_axe");
        add("firon_shovel", "minecraft:netherite_shovel", "distant_worlds:firon_shovel");
        add("firon_hoe", "minecraft:netherite_hoe", "distant_worlds:firon_hoe");
        add("firon_helmet", "minecraft:netherite_helmet", "distant_worlds:firon_helmet");
        add("firon_chestplate", "minecraft:netherite_chestplate", "distant_worlds:firon_chestplate");
        add("firon_leggings", "minecraft:netherite_leggings", "distant_worlds:firon_leggings");
        add("firon_boots", "minecraft:netherite_boots", "distant_worlds:firon_boots");

        List<IngredientSpec> tools = List.of(
                spec("distant_worlds:firon_sword"),
                spec("distant_worlds:firon_pickaxe"),
                spec("distant_worlds:firon_axe"),
                spec("distant_worlds:firon_shovel"),
                spec("distant_worlds:firon_hoe"));
        add("firon_multitool", spec("distant_worlds:firon_ingot"), tools, 3,
                item("distant_worlds:firon_multitool"));
    }

    private LithumAltarRecipeResolver() {}

    private static void add(String current, String coreId, String outputId) {
        add(current, spec(coreId), List.of(
                spec("distant_worlds:firon_ingot"),
                spec("distant_worlds:firon_ingot"),
                spec("distant_worlds:firon_ingot"),
                spec("distant_worlds:firon_ingot")), 4, item(outputId));
    }

    private static void add(String current, IngredientSpec core, List<IngredientSpec> pedestal,
                            int empty, ItemStack output) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MOD_ID, current);
        DEFINITIONS.put(id, new LithumAltarRecipeDefinition(
                current, core, pedestal, empty, output, 200_000, 2_000));
    }

    private static IngredientSpec spec(String id) {
        ItemStack stack = item(id);
        return stack.isEmpty() ? IngredientSpec.EMPTY : new IngredientSpec(Ingredient.of(stack), 1);
    }

    private static ItemStack item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) return ItemStack.EMPTY;
        var item = BuiltInRegistries.ITEM.get(key);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    public static List<LithumAltarRecipeDefinition> definitions() {
        return List.copyOf(DEFINITIONS.values());
    }

    public static LithumAltarRecipeDefinition resolve(ResourceLocation id) {
        return DEFINITIONS.get(id);
    }

    public static LithumAltarRecipeDefinition resolveCurrentRecipe(String currentRecipe) {
        if (currentRecipe == null || currentRecipe.isEmpty()) return null;
        for (LithumAltarRecipeDefinition definition : DEFINITIONS.values()) {
            if (definition.currentRecipe().equals(currentRecipe)) return definition;
        }
        return null;
    }

    public static boolean isFiron(ResourceLocation id) {
        return id != null && MOD_ID.equals(id.getNamespace()) && id.getPath().startsWith("firon_");
    }

    public static boolean isComplete(LithumAltarRecipeDefinition definition) {
        if (definition == null || definition.output().isEmpty()) return false;
        if (definition.coreInput() == null || definition.coreInput().isEmpty()) return false;
        for (IngredientSpec spec : definition.pedestalInputs()) {
            if (spec == null || spec.isEmpty()) return false;
        }
        return true;
    }
}
