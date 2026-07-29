package com.huanghuang.rsintegration.mods.pmmo;

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
import net.minecraftforge.registries.ForgeRegistries;

/** Runtime recipe exposing one target output from a PMMO salvage table. */
public final class PmmoSalvageRecipeWrapper implements Recipe<Container> {
    private final ResourceLocation id;
    private final PmmoSalvageDefinition definition;
    private final PmmoSalvageDefinition.Output target;

    PmmoSalvageRecipeWrapper(PmmoSalvageDefinition definition,
                             PmmoSalvageDefinition.Output target) {
        this.definition = definition;
        this.target = target;
        this.id = recipeId(definition.inputId(), target.outputId());
    }

    public PmmoSalvageDefinition definition() { return definition; }
    public PmmoSalvageDefinition.Output target() { return target; }

    public static ResourceLocation recipeId(ResourceLocation input, ResourceLocation output) {
        return new ResourceLocation("rs_integration", "pmmo_salvage/"
                + input.getNamespace() + "/" + input.getPath() + "/"
                + output.getNamespace() + "/" + output.getPath());
    }

    @Override public boolean matches(Container container, Level level) { return false; }
    @Override public ItemStack assemble(Container container, RegistryAccess access) { return getResultItem(access); }
    @Override public boolean canCraftInDimensions(int width, int height) { return true; }
    @Override public ResourceLocation getId() { return id; }
    @Override public RecipeSerializer<?> getSerializer() { return null; }
    @Override public RecipeType<?> getType() { return null; }

    @Override
    public ItemStack getResultItem(RegistryAccess access) {
        var item = ForgeRegistries.ITEMS.getValue(target.outputId());
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        var item = ForgeRegistries.ITEMS.getValue(definition.inputId());
        if (item != null) ingredients.add(Ingredient.of(new ItemStack(item)));
        return ingredients;
    }
}
