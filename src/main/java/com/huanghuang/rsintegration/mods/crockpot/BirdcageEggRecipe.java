package com.huanghuang.rsintegration.mods.crockpot;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/** Server-side form of CrockPot's JEI-only birdcage meat-to-egg displays. */
public final class BirdcageEggRecipe implements Recipe<Container> {
    private final ResourceLocation id;
    private final Ingredient ingredient;
    private final boolean monsterMeat;
    private final ItemStack displayOutput;

    BirdcageEggRecipe(ResourceLocation id, Ingredient ingredient, boolean monsterMeat,
                      ItemStack displayOutput) {
        this.id = id;
        this.ingredient = ingredient;
        this.monsterMeat = monsterMeat;
        this.displayOutput = displayOutput.copy();
    }

    public Ingredient ingredient() { return ingredient; }
    public boolean monsterMeat() { return monsterMeat; }

    @Override public boolean matches(Container container, Level level) {
        return !container.isEmpty() && ingredient.test(container.getItem(0));
    }
    @Override public ItemStack assemble(Container container, RegistryAccess access) { return displayOutput.copy(); }
    @Override public boolean canCraftInDimensions(int width, int height) { return true; }
    @Override public ItemStack getResultItem(RegistryAccess access) { return displayOutput.copy(); }
    @Override public ResourceLocation getId() { return id; }
    @Override public RecipeSerializer<?> getSerializer() { return null; }
    @Override public RecipeType<?> getType() { return null; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        ingredients.add(ingredient);
        return ingredients;
    }
}
