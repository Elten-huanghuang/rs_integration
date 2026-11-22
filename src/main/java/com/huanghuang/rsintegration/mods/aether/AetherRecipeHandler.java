package com.huanghuang.rsintegration.mods.aether;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.AbstractRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class AetherRecipeHandler extends AbstractRecipeHandler {

    static {
        registerRecipePrefixes(AetherRecipeHandler.class, "com.aetherteam.aether.recipe.recipes.item.");
    }

    @Override
    public ModType modType() { return ModType.byId(ModIds.AETHER); }

    @Override
    public ModType modType(Recipe<?> recipe) {
        ModType machine = machineTypeFor(recipe);
        return machine != null ? machine : modType();
    }

    /** 预览、执行和绑定共用注册类型，避免子机器绑定被误当成天境通用绑定。 */
    @Nullable
    public static ModType machineTypeFor(Recipe<?> recipe) {
        RecipeType<?> type = recipe.getType();
        return type == null ? null : machineTypeForRecipeType(ForgeRegistries.RECIPE_TYPES.getKey(type));
    }

    @Nullable
    static ModType machineTypeForRecipeType(@Nullable ResourceLocation typeId) {
        if (typeId == null || !ModIds.AETHER.equals(typeId.getNamespace())) return null;
        return switch (typeId.getPath()) {
            case "freezing" -> ModType.findById("aether_freezer");
            case "incubation" -> ModType.findById("aether_incubator");
            case "enchanting", "repairing" -> ModType.findById("aether_altar");
            default -> null;
        };
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // IncubationRecipe produces an entity, not an item — no RS-relevant output.
        if (recipe.getClass().getSimpleName().equals("IncubationRecipe"))
            return ItemStack.EMPTY;
        return ModRecipeHandlers.probeResultItem(recipe, access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty())
                specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }
}
