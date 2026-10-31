package com.almostreliable.summoningrituals.recipe;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.List;

/** 测试用公开访问器，覆盖不加载可选模组时的反射配方合同。 */
public final class AltarRecipe implements Recipe<Container> {
    public record Input(Ingredient ingredient, int count) {}

    private final Ingredient catalyst;
    private final List<Input> inputs;

    public AltarRecipe(Ingredient catalyst, List<Input> inputs) {
        this.catalyst = catalyst;
        this.inputs = List.copyOf(inputs);
    }

    public Ingredient getCatalyst() { return catalyst; }
    public List<Input> getInputs() { return inputs; }
    @Override public ResourceLocation getId() { return new ResourceLocation("test", "summoning"); }
    @Override public boolean matches(Container inventory, Level level) { return false; }
    @Override public ItemStack assemble(Container inventory, RegistryAccess access) { return ItemStack.EMPTY; }
    @Override public boolean canCraftInDimensions(int width, int height) { return false; }
    @Override public ItemStack getResultItem(RegistryAccess access) { return ItemStack.EMPTY; }
    @Override public RecipeSerializer<?> getSerializer() { return null; }
    @Override public RecipeType<?> getType() { return RecipeType.CRAFTING; }
}
