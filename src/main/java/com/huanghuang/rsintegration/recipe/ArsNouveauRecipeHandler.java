package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsRecipeClassifier;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsTileAccess;
import com.huanghuang.rsintegration.reflection.probes.ArsNouveauReflection;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Recipe handler for Ars Nouveau Imbuement and Enchanting Apparatus recipes.
 *
 * <p>Only two of Ars' 16 recipe types are automatable: {@code imbuement} and
 * {@code enchanting_apparatus}. All others (enchantment, glyph, crush, dye,
 * potion_flask, book_upgrade, armor_upgrade, spell_write, reactive_enchantment,
 * caster_tome, summon_ritual, budding_conversion, dispel_entity, scry_ritual)
 * either mutate NBT on an existing item, target entities/world, or have no
 * deterministic item output, and are deliberately excluded.</p>
 *
 * <p><strong>Key implementation notes:</strong></p>
 * <ul>
 *   <li>{@code ImbuementRecipe.assemble()} and {@code getResultItem()} both
 *       return {@code ItemStack.EMPTY}. Must call {@code getResult(tile)} to
 *       read the {@code output} field (which is a shared instance and must be
 *       copied before use).</li>
 *   <li>{@code EnchantingApparatusRecipe} has four NBT-transforming subclasses
 *       (EnchantmentRecipe, ArmorUpgradeRecipe, SpellWriteRecipe,
 *       ReactiveEnchantmentRecipe). Classification MUST use the recipe type
 *       registry ID, NOT {@code instanceof}, to avoid including those subclasses.</li>
 *   <li>This handler does NOT call back to {@code RecipeIndex.tryGetResultItem}
 *       to avoid recursion (the codebase has a history of
 *       {@code tryGetResultItem} recursion stack overflows).</li>
 *   <li>Source cost is NOT modeled as an ingredient — it's a per-tile integer
 *       resource handled by the delegate.</li>
 * </ul>
 */
public final class ArsNouveauRecipeHandler extends AbstractRecipeHandler {

    static {
        registerRecipePrefixes(ArsNouveauRecipeHandler.class,
                "com.hollingsworth.arsnouveau.common.crafting.recipes.",
                "com.hollingsworth.arsnouveau.api.enchanting_apparatus.");
    }

    @Override
    public ModType modType() {
        return ModType.byId(ModIds.ID_ARS_IMBUEMENT);  // Primary type
    }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        // First check class prefix
        if (!super.canHandle(recipe)) return false;

        // Then verify it's an automatable recipe type
        String typeId = ArsTileAccess.recipeTypeId(recipe);
        return ArsRecipeClassifier.isAutomatable(typeId);
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        String typeId = ArsTileAccess.recipeTypeId(recipe);

        if (ArsRecipeClassifier.isImbuement(typeId)) {
            return getImbuementResult(recipe);
        } else if (ArsRecipeClassifier.isApparatus(typeId)) {
            return getApparatusResult(recipe);
        }

        RSIntegrationMod.LOGGER.warn("ArsNouveauRecipeHandler: unknown automatable recipe type {}", typeId);
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        String typeId = ArsTileAccess.recipeTypeId(recipe);

        if (ArsRecipeClassifier.isImbuement(typeId)) {
            return getImbuementIngredients(recipe);
        } else if (ArsRecipeClassifier.isApparatus(typeId)) {
            return getApparatusIngredients(recipe);
        }

        return null;
    }

    // ── Imbuement ─────────────────────────────────────────────────────────────

    private ItemStack getImbuementResult(Recipe<?> recipe) {
        // ImbuementRecipe.assemble() and getResultItem() both return EMPTY.
        // Must read the `output` field directly (it's a shared instance, must copy).
        return Reflect.<ItemStack>getField(recipe, "output")
                .map(ItemStack::copy)
                .orElse(ItemStack.EMPTY);
    }

    @Nullable
    private List<IngredientSpec> getImbuementIngredients(Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();

        // Input item (single ingredient)
        Reflect.<Ingredient>getField(recipe, "input").ifPresent(ing -> {
            if (!ing.isEmpty()) {
                specs.add(new IngredientSpec(ing, 1));
            }
        });

        // Pedestal items (for recipes like elemental essences that require pedestals)
        Reflect.<List<Ingredient>>getField(recipe, "pedestalItems").ifPresent(pedestalList -> {
            if (pedestalList != null) {
                for (Ingredient ing : pedestalList) {
                    if (!ing.isEmpty()) {
                        specs.add(new IngredientSpec(ing, 1));
                    }
                }
            }
        });

        return specs.isEmpty() ? null : specs;
    }

    // ── Enchanting Apparatus ──────────────────────────────────────────────────

    private ItemStack getApparatusResult(Recipe<?> recipe) {
        // EnchantingApparatusRecipe has a `result` field (ItemStack)
        return Reflect.<ItemStack>getField(recipe, "result")
                .map(ItemStack::copy)
                .orElse(ItemStack.EMPTY);
    }

    @Nullable
    private List<IngredientSpec> getApparatusIngredients(Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();

        // Reagent (central catalyst item)
        Reflect.<Ingredient>getField(recipe, "reagent").ifPresent(ing -> {
            if (!ing.isEmpty()) {
                specs.add(new IngredientSpec(ing, 1));
            }
        });

        // Pedestal ingredients
        Reflect.<List<Ingredient>>getField(recipe, "pedestalItems").ifPresent(pedestalList -> {
            if (pedestalList != null) {
                for (Ingredient ing : pedestalList) {
                    if (!ing.isEmpty()) {
                        specs.add(new IngredientSpec(ing, 1));
                    }
                }
            }
        });

        return specs.isEmpty() ? null : specs;
    }
}
