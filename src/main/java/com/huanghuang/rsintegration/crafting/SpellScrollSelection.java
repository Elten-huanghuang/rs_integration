package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/** Selection policy for recipes that accept any Iron's Spells scroll. */
final class SpellScrollSelection {
    private static final ResourceLocation SCROLL_ID =
            new ResourceLocation("irons_spellbooks", "scroll");

    private SpellScrollSelection() {}

    static boolean acceptsAnyScroll(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) return false;
        Item scroll = ForgeRegistries.ITEMS.getValue(SCROLL_ID);
        if (scroll == null) return false;

        boolean found = false;
        for (ItemStack option : ingredient.getItems()) {
            if (option == null || option.isEmpty()) continue;
            if (!SCROLL_ID.equals(ForgeRegistries.ITEMS.getKey(option.getItem()))) return false;
            found = true;
        }
        if (!found) return false;

        try {
            // Exact spell/level ingredients reject a tagless scroll. Only the
            // broad item ingredient used by the Essence Smoker reaches here.
            return ingredient.test(new ItemStack(scroll));
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    static int level(ItemStack stack) {
        IronSpellBooksRecipeCatalog.SpellScrollKey key = key(stack);
        return key == null ? Integer.MAX_VALUE : key.level();
    }

    static int rarity(ItemStack stack) {
        return rarity(key(stack));
    }

    static String spellId(ItemStack stack) {
        IronSpellBooksRecipeCatalog.SpellScrollKey key = key(stack);
        return key == null ? "~" : key.spellId().toString();
    }

    static int compareKeys(@Nullable IronSpellBooksRecipeCatalog.SpellScrollKey left,
                           @Nullable IronSpellBooksRecipeCatalog.SpellScrollKey right) {
        int costCompare = compareCost(
                rarity(left), left == null ? Integer.MAX_VALUE : left.level(),
                rarity(right), right == null ? Integer.MAX_VALUE : right.level());
        if (costCompare != 0) return costCompare;
        String leftId = left == null ? "~" : left.spellId().toString();
        String rightId = right == null ? "~" : right.spellId().toString();
        return leftId.compareTo(rightId);
    }

    static int compareCost(int leftRarity, int leftLevel,
                           int rightRarity, int rightLevel) {
        int rarityCompare = Integer.compare(leftRarity, rightRarity);
        if (rarityCompare != 0) return rarityCompare;
        return Integer.compare(leftLevel, rightLevel);
    }

    private static int rarity(@Nullable IronSpellBooksRecipeCatalog.SpellScrollKey key) {
        if (key == null) return Integer.MAX_VALUE;
        try {
            AbstractSpell spell = SpellRegistry.getSpell(key.spellId());
            if (spell == null || spell == SpellRegistry.none()) return Integer.MAX_VALUE;
            var rarity = spell.getRarity(key.level());
            return rarity == null ? Integer.MAX_VALUE : rarity.ordinal();
        } catch (RuntimeException | LinkageError ignored) {
            return Integer.MAX_VALUE;
        }
    }

    @Nullable
    private static IronSpellBooksRecipeCatalog.SpellScrollKey key(ItemStack stack) {
        if (stack == null || stack.isEmpty()
                || !SCROLL_ID.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()))) {
            return null;
        }
        try {
            return IronSpellBooksRecipeCatalog.spellScrollKey(stack);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
