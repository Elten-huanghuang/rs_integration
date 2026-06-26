package com.huanghuang.rsintegration.mods.forbidden;

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

import javax.annotation.Nullable;

/**
 * Lightweight {@link Recipe} wrapper for a Forbidden Arcanus
 * {@code Ritual} object so it can be stored in {@code RecipeIndex}
 * and discovered by the crafting plan resolver.
 *
 * <p>FA rituals live in {@code FARegistries.RITUAL}, not in the
 * vanilla {@code RecipeManager}.  This wrapper bridges the gap.</p>
 */
public final class FaRitualWrapper implements Recipe<Container> {

    private final ResourceLocation id;
    private final Object ritual;
    private final ItemStack resultItem;
    private final boolean isUpgrade;
    private final int upgradeFromTier;
    private final int upgradeToTier;

    /** Create-item-result wrapper. */
    public FaRitualWrapper(ResourceLocation id, Object ritual, ItemStack resultItem) {
        this.id = id;
        this.ritual = ritual;
        this.resultItem = resultItem;
        this.isUpgrade = false;
        this.upgradeFromTier = 0;
        this.upgradeToTier = 0;
    }

    /** Upgrade-tier-result wrapper. */
    public FaRitualWrapper(ResourceLocation id, Object ritual, ItemStack mainIngredientStack,
                           int fromTier, int toTier) {
        this.id = id;
        this.ritual = ritual;
        this.resultItem = mainIngredientStack.copy();
        this.resultItem.setHoverName(net.minecraft.network.chat.Component.translatable(
                "rsi.fa.plan.upgrade_title", fromTier, toTier));
        this.isUpgrade = true;
        this.upgradeFromTier = fromTier;
        this.upgradeToTier = toTier;
    }

    public Object ritual() { return ritual; }
    public boolean isUpgrade() { return isUpgrade; }
    public int upgradeFromTier() { return upgradeFromTier; }
    public int upgradeToTier() { return upgradeToTier; }

    // ── Recipe contract ──────────────────────────────────────────

    @Override
    public boolean matches(Container container, Level level) { return false; }

    @Override
    public ItemStack assemble(Container container, RegistryAccess access) {
        return resultItem.copy();
    }

    @Override
    public boolean canCraftInDimensions(int w, int h) { return true; }

    @Override
    public ItemStack getResultItem(RegistryAccess access) { return resultItem.copy(); }

    @Override
    public ResourceLocation getId() { return id; }

    @Override
    public RecipeSerializer<?> getSerializer() { return null; }

    @Override
    public RecipeType<?> getType() { return null; }

    @Override
    public NonNullList<Ingredient> getIngredients() { return NonNullList.create(); }

    // ── Object contract ──────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FaRitualWrapper that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() { return "FaRitualWrapper{" + id + '}'; }
}
