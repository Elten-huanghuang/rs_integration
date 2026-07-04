package com.huanghuang.rsintegration.mods.farmingforblockheads;

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

import java.util.UUID;

/** Virtual Recipe wrapper for FarmingForBlockheads market entries. */
public final class MarketRecipeWrapper implements Recipe<Container> {

    private final ResourceLocation id;
    private final UUID entryId;
    private final ItemStack output;
    private final ItemStack cost;

    public MarketRecipeWrapper(UUID entryId, ItemStack output, ItemStack cost) {
        this.entryId = entryId;
        this.id = new ResourceLocation("farmingforblockheads", "market/" + entryId);
        this.output = output;
        this.cost = cost;
    }

    public UUID entryId() { return entryId; }
    public ItemStack costItem() { return cost.copy(); }

    @Override
    public boolean matches(Container container, Level level) { return false; }

    @Override
    public ItemStack assemble(Container container, RegistryAccess access) {
        return output.copy();
    }

    @Override
    public boolean canCraftInDimensions(int w, int h) { return true; }

    @Override
    public ItemStack getResultItem(RegistryAccess access) { return output.copy(); }

    @Override
    public ResourceLocation getId() { return id; }

    @Override
    public RecipeSerializer<?> getSerializer() { return null; }

    @Override
    public RecipeType<?> getType() { return null; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(Ingredient.of(cost.copy()));
        return list;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MarketRecipeWrapper that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() { return "MarketRecipeWrapper{" + id + '}'; }
}
