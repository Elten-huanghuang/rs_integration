package com.huanghuang.rsintegration.mods.crabbersdelight;

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

/**
 * Virtual {@link Recipe} wrapper for a CrabbersDelight crab trap loot entry.
 * Crab trap bait→loot mappings are driven by loot tables, not standard
 * recipes, so this wrapper bridges the gap for RS auto-crafting.
 */
public final class CrabTrapLootWrapper implements Recipe<Container> {

    private final ResourceLocation id;
    private final ItemStack bait;

    public CrabTrapLootWrapper(ResourceLocation id, ItemStack bait) {
        this.id = id;
        this.bait = bait;
    }

    public ItemStack baitItem() { return bait.copy(); }

    @Override
    public boolean matches(Container container, Level level) { return false; }

    @Override
    public ItemStack assemble(Container container, RegistryAccess access) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int w, int h) { return true; }

    @Override
    public ItemStack getResultItem(RegistryAccess access) {
        return ItemStack.EMPTY;
    }

    @Override
    public ResourceLocation getId() { return id; }

    @Override
    public RecipeSerializer<?> getSerializer() { return null; }

    @Override
    public RecipeType<?> getType() { return null; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(Ingredient.of(bait.copy()));
        return list;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CrabTrapLootWrapper that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() { return "CrabTrapLootWrapper{" + id + '}'; }
}
