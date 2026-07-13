package com.huanghuang.rsintegration.crafting;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;

/** Shared ingredient matching for stateful items whose semantic state may use
 * different numeric NBT tag types across CraftTweaker and the owning mod. */
public final class IngredientMatcher {
    private static final ResourceLocation EARTH_HEART =
            new ResourceLocation("enigmaticlegacy", "earth_heart");

    private IngredientMatcher() {}

    public static boolean test(Ingredient ingredient, ItemStack actual) {
        if (ingredient.test(actual)) return true;
        if (actual.isEmpty() || !EARTH_HEART.equals(ForgeRegistries.ITEMS.getKey(actual.getItem()))) {
            return false;
        }
        // CraftTweaker's `.withTag({isTainted: 1})` may serialize the template as
        // IntTag while Enigmatic Legacy writes its ITaintable flag as ByteTag.
        // Strict NBT comparison rejects that representation mismatch even though
        // both mean true. Scope the semantic bridge to this one documented state.
        if (actual.getTag() == null || !actual.getTag().getBoolean("isTainted")) return false;
        for (ItemStack template : ingredient.getItems()) {
            if (template.isEmpty() || template.getItem() != actual.getItem() || template.getTag() == null) continue;
            if (template.getTag().contains("isTainted")
                    && template.getTag().getBoolean("isTainted")) {
                return true;
            }
        }
        return false;
    }
}
