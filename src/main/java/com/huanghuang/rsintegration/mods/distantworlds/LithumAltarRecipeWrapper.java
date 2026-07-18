package com.huanghuang.rsintegration.mods.distantworlds;

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

/** Recipe bridge for the non-Recipe Lithum Altar Firon definitions. */
public final class LithumAltarRecipeWrapper implements Recipe<Container> {
    private final ResourceLocation id;
    private final LithumAltarRecipeDefinition definition;

    public LithumAltarRecipeWrapper(ResourceLocation id, LithumAltarRecipeDefinition definition) {
        this.id = id;
        this.definition = definition;
    }

    public LithumAltarRecipeDefinition definition() { return definition; }

    @Override public boolean matches(Container container, Level level) { return false; }
    @Override public ItemStack assemble(Container container, RegistryAccess access) { return getResultItem(access); }
    @Override public boolean canCraftInDimensions(int width, int height) { return true; }
    @Override public ItemStack getResultItem(RegistryAccess access) { return definition.output().copy(); }
    @Override public ResourceLocation getId() { return id; }
    @Override public RecipeSerializer<?> getSerializer() { return null; }
    @Override public RecipeType<?> getType() { return null; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> result = NonNullList.create();
        for (var spec : definition.allMaterials()) result.add(spec.ingredient());
        return result;
    }

    @Override public boolean equals(Object other) {
        return other instanceof LithumAltarRecipeWrapper wrapper && id.equals(wrapper.id);
    }

    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() { return "LithumAltarRecipeWrapper{" + id + '}'; }
}
