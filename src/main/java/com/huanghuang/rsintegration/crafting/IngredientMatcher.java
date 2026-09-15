package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.recipe.SlashBladeRecipeHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.item.Item;

/** Shared ingredient matching for stateful items whose semantic state may use
 * different numeric NBT tag types across CraftTweaker and the owning mod. */
public final class IngredientMatcher {
    private static final ResourceLocation EARTH_HEART =
            new ResourceLocation("enigmaticlegacy", "earth_heart");
    private static final ResourceLocation IRON_SPELL_SCROLL =
            new ResourceLocation("irons_spellbooks", "scroll");

    private IngredientMatcher() {}

    public static boolean hasCompleteItemList(Ingredient ingredient) {
        Class<?> type = ingredient.getClass();
        return type == Ingredient.class || type == StrictNBTIngredient.class
                || type == net.minecraftforge.common.crafting.PartialNBTIngredient.class;
    }

    /** Null means the predicate may match outside its display items; never filter those ingredients. */
    public static Set<Item> itemTypesForMatching(Ingredient ingredient) {
        if (!hasCompleteItemList(ingredient)) return null;
        Set<Item> types = new HashSet<>();
        for (ItemStack template : ingredient.getItems()) {
            if (!template.isEmpty()) types.add(template.getItem());
        }
        return Set.copyOf(types);
    }

    /** A single unknown predicate requires the complete inventory candidate set. */
    public static Set<Item> itemTypesForAll(Iterable<Ingredient> ingredients) {
        Set<Item> types = new HashSet<>();
        try {
            for (Ingredient ingredient : ingredients) {
                Set<Item> candidates = itemTypesForMatching(ingredient);
                if (candidates == null) return null;
                types.addAll(candidates);
            }
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
        return Set.copyOf(types);
    }

    /**
     * Match a graph material without losing state that cannot be reconstructed as
     * a fully initialized mod ItemStack (notably SlashBlade capability state).
     */
    public static boolean test(Ingredient ingredient, MaterialKey actual) {
        Objects.requireNonNull(actual, "actual");
        return test(ingredient, actual.toStack(1))
                || SlashBladeRecipeHandler.matchesMaterialKey(ingredient, actual);
    }

    public static boolean test(Ingredient ingredient, CraftingResolver.StackKey actual) {
        Objects.requireNonNull(actual, "actual");
        return test(ingredient, new MaterialKey(actual.item(), actual.tag()));
    }

    public static boolean test(Ingredient ingredient, ItemStack actual) {
        boolean statefulTool = false;
        for (ItemStack template : ingredient.getItems()) {
            if (matchesWaterBottleIgnoringPurity(template, actual)) return true;
            if (matchesSpellScrollSemantics(template, actual)) return true;
            if (template.getItem() == actual.getItem() && template.getMaxDamage() > 0
                    && isUnbreakable(template.getTag()) && requiresNbt(ingredient)) {
                statefulTool = true;
                if (!isUnbreakable(actual.getTag())) continue;
                if (isDefaultDamage(template.getTag()) && !isDefaultDamage(actual.getTag())) continue;
                ItemStack normalized = actual.copy();
                CompoundTag tag = normalized.getTag();
                tag.put("Unbreakable", template.getTag().get("Unbreakable").copy());
                if (isDefaultDamage(template.getTag()) && isDefaultDamage(tag)) {
                    if (template.getTag().contains("Damage")) {
                        tag.put("Damage", template.getTag().get("Damage").copy());
                    } else {
                        tag.remove("Damage");
                    }
                }
                if (ingredient.test(normalized)) return true;
                // StrictNBTIngredient is still strict for ordinary tagged
                // items. A pristine unbreakable tool is the exception: its
                // RepairCost, itemModifier, and similar fields are runtime
                // state that smithing deliberately carries forward. Require
                // every template field, but do not reject extra runtime data.
                if (nbtMatches(template.getTag(), actual.getTag(), true)) return true;
            }
        }
        if (statefulTool) return false;
        if (ingredient.test(actual)) return true;
        if (matchesTaglessPristineTool(ingredient, actual)) return true;
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

    /**
     * Some machine ingredients implement a tagless item predicate by delegating
     * to a strict stack comparison. Runtime metadata on a pristine unbreakable
     * tool must not make that ordinary item ingredient reject the tool.
     */
    private static boolean matchesTaglessPristineTool(Ingredient ingredient, ItemStack actual) {
        if (actual.isEmpty() || actual.getMaxDamage() <= 0 || !isUnbreakable(actual.getTag())
                || !isDefaultDamage(actual.getTag()) || requiresNbt(ingredient)) return false;
        for (ItemStack template : ingredient.getItems()) {
            if (template.isEmpty() || template.getItem() != actual.getItem()
                    || template.hasTag()) continue;
            try {
                if (ingredient.test(new ItemStack(template.getItem()))) return true;
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     * Starter/tool loot commonly serializes an otherwise pristine item as
     * {@code {Damage:0,Unbreakable:1}}, while CraftTweaker's strict template
     * contains only {@code {Unbreakable:1}}. Treat those two representations as
     * equal without weakening any other strict NBT ingredient.
     */
    public static boolean nbtMatches(CompoundTag expected, CompoundTag actual, boolean partial) {
        if (isUnbreakable(expected)) {
            if (!isUnbreakable(actual)) return false;
            boolean pristine = isDefaultDamage(expected);
            if (pristine && !isDefaultDamage(actual)) return false;
            expected = expected.copy();
            actual = actual.copy();
            expected.putBoolean("Unbreakable", true);
            actual.putBoolean("Unbreakable", true);
            if (pristine) {
                expected.remove("Damage");
                actual.remove("Damage");
            }
        }
        return partial ? matchesPartialTag(expected, actual)
                : Objects.equals(expected, actual);
    }

    private static boolean matchesPartialTag(CompoundTag expected, CompoundTag actual) {
        if (expected == null || actual == null) return expected == actual;
        for (String key : expected.getAllKeys()) {
            if (!actual.contains(key)) return false;
            Tag expectedValue = expected.get(key);
            Tag actualValue = actual.get(key);
            if ("Unbreakable".equals(key)) {
                if (!(expectedValue instanceof net.minecraft.nbt.NumericTag expectedNumeric)
                        || !(actualValue instanceof net.minecraft.nbt.NumericTag actualNumeric)
                        || expectedNumeric.getAsInt() != actualNumeric.getAsInt()) return false;
            } else if (expectedValue instanceof CompoundTag expectedCompound
                    && actualValue instanceof CompoundTag actualCompound) {
                if (!matchesPartialTag(expectedCompound, actualCompound)) return false;
            } else if (!NbtUtils.compareNbt(expectedValue, actualValue, true)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Match a produced stack against a player-selected output template. A
     * pristine unbreakable tool may legitimately retain runtime state such as
     * RepairCost or an item modifier while it is upgraded; that state must not
     * make the final product fall through to storage. Other tagged outputs stay
     * exact, and a damaged tool never matches a pristine template.
     */
    public static boolean matchesProducedOutput(ItemStack expected, ItemStack actual) {
        if (expected == null || actual == null || expected.isEmpty() || actual.isEmpty()
                || expected.getItem() != actual.getItem()) return false;
        if (!expected.hasTag()) return true;
        if (ItemStack.isSameItemSameTags(expected, actual)) return true;
        return expected.getMaxDamage() > 0 && isUnbreakable(expected.getTag())
                && nbtMatches(expected.getTag(), actual.getTag(), true);
    }

    /** Compare a concrete stack while preserving semantic equality for pristine tools. */
    public static boolean matchesRuntimeIdentity(ItemStack expected, ItemStack actual) {
        if (expected == null || actual == null || expected.isEmpty() || actual.isEmpty()
                || expected.getItem() != actual.getItem()) return false;
        if (expected.getMaxDamage() > 0 && isUnbreakable(expected.getTag())) {
            return matchesProducedOutput(expected, actual);
        }
        return ItemStack.isSameItemSameTags(expected, actual);
    }

    private static boolean isUnbreakable(CompoundTag tag) {
        return tag != null && (tag.contains("Unbreakable", Tag.TAG_BYTE)
                || tag.contains("Unbreakable", Tag.TAG_INT)) && tag.getInt("Unbreakable") != 0;
    }

    private static boolean isDefaultDamage(CompoundTag tag) {
        if (!tag.contains("Damage")) return true;
        return tag.contains("Damage", Tag.TAG_ANY_NUMERIC)
                && ((net.minecraft.nbt.NumericTag) tag.get("Damage")).getAsDouble() == 0;
    }

    /** Iron 3.15 rewrote scroll-container NBT while retaining spell identity. */
    private static boolean matchesSpellScrollSemantics(ItemStack expected, ItemStack actual) {
        if (expected == null || actual == null || expected.isEmpty() || actual.isEmpty()
                || expected.getItem() != actual.getItem()) return false;
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(expected.getItem());
        if (!IRON_SPELL_SCROLL.equals(itemId)) return false;
        try {
            return com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog
                    .sameSpellScroll(expected, actual);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Returns whether this ingredient actually rejects tagless stacks of every
     * displayed item. A tagged display stack alone does not imply strict NBT:
     * vanilla {@link Ingredient} compares only the item, even when it was built
     * from an ItemStack carrying damage or other tags.
     */
    public static boolean requiresNbt(Ingredient ingredient) {
        if (ingredient instanceof StrictNBTIngredient) return true;
        boolean hasTaggedCandidate = false;
        for (ItemStack candidate : ingredient.getItems()) {
            if (candidate.isEmpty()) continue;
            hasTaggedCandidate |= candidate.hasTag();
            try {
                if (ingredient.test(new ItemStack(candidate.getItem()))) return false;
            } catch (RuntimeException ignored) {
                // Some modded ingredients assume their probe already contains
                // capability/NBT state. Treat an invalid tagless probe as a
                // rejection instead of allowing third-party code to crash the
                // planning tick.
            }
        }
        return hasTaggedCandidate;
    }

    /** Thirst Was Taken adds a non-brewing "Purity" tag to water bottles. */
    public static boolean matchesWaterBottleIgnoringPurity(ItemStack expected, ItemStack actual) {
        if (expected.isEmpty() || actual.isEmpty()
                || expected.getItem() != actual.getItem()
                || !isVanillaPotionContainer(expected)
                || PotionUtils.getPotion(expected) != Potions.WATER
                || PotionUtils.getPotion(actual) != Potions.WATER) return false;
        return potionTagsEqualIgnoringPurity(expected, actual);
    }

    /** Match an exact vanilla potion state while ignoring Thirst's non-brewing Purity metadata. */
    public static boolean matchesPotionIgnoringPurity(ItemStack expected, ItemStack actual) {
        if (expected.isEmpty() || actual.isEmpty()
                || expected.getItem() != actual.getItem()
                || !isVanillaPotionContainer(expected)
                || PotionUtils.getPotion(expected) != PotionUtils.getPotion(actual)) return false;
        return potionTagsEqualIgnoringPurity(expected, actual);
    }

    private static boolean potionTagsEqualIgnoringPurity(ItemStack expected, ItemStack actual) {
        var expectedTag = expected.getTag() == null ? null : expected.getTag().copy();
        var actualTag = actual.getTag() == null ? null : actual.getTag().copy();
        if (expectedTag != null) expectedTag.remove("Purity");
        if (actualTag != null) actualTag.remove("Purity");
        return java.util.Objects.equals(expectedTag, actualTag);
    }

    private static boolean isVanillaPotionContainer(ItemStack stack) {
        return stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION)
                || stack.is(Items.LINGERING_POTION);
    }
}
