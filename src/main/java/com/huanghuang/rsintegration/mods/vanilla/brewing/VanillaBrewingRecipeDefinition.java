package com.huanghuang.rsintegration.mods.vanilla.brewing;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

/** Runtime brewing-registry edge exposed to RSI's ordinary recipe planner. */
public final class VanillaBrewingRecipeDefinition implements Recipe<Container> {
    private final ResourceLocation id;
    private final ItemStack input;
    private final ItemStack reagent;
    private final ItemStack output;

    VanillaBrewingRecipeDefinition(ResourceLocation id, ItemStack input,
                                    ItemStack reagent, ItemStack output) {
        this.id = id;
        this.input = input.copyWithCount(1);
        this.reagent = reagent.copyWithCount(1);
        this.output = output.copyWithCount(1);
    }

    public ItemStack input() { return input.copy(); }
    public ItemStack reagent() { return reagent.copy(); }
    public ItemStack output() { return output.copyWithCount(3); }
    public ItemStack outputUnit() { return output.copyWithCount(1); }

    @Override public boolean matches(Container container, Level level) { return false; }
    @Override public ItemStack assemble(Container container, net.minecraft.core.RegistryAccess access) { return output(); }
    @Override public boolean canCraftInDimensions(int width, int height) { return false; }
    @Override public ItemStack getResultItem(net.minecraft.core.RegistryAccess access) { return output(); }
    @Override public ResourceLocation getId() { return id; }
    @Override public RecipeSerializer<?> getSerializer() { return RecipeSerializer.SHAPELESS_RECIPE; }
    @Override public RecipeType<?> getType() { return RecipeType.CRAFTING; }
    @Override public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        ingredients.add(StrictNBTIngredient.of(input.copy()));
        ingredients.add(Ingredient.of(reagent.copy()));
        ingredients.add(Ingredient.of(Items.BLAZE_POWDER));
        return ingredients;
    }
}
