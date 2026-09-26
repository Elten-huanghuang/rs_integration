package com.huanghuang.rsintegration.recipe;
import java.lang.reflect.Field;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsApparatusMaterials;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsDynamicApparatusRecipe;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsGlyphMaterials;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsImbuementMaterials;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsRecipeClassifier;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsTileAccess;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Recipe handler for Ars Nouveau Imbuement, Enchanting Apparatus, and glyph recipes.
 *
 * <p>Imbuement and ordinary Apparatus recipes expose fixed inputs and outputs.
 * Enchantment recipes have deterministic enchanted-book inputs and outputs,
 * so every level is indexed for recursive crafting. Armor upgrades still need
 * the concrete NBT-bearing armor selected through JEI.</p>
 *
 * <p><strong>Key implementation notes:</strong></p>
 * <ul>
 *   <li>{@code ImbuementRecipe.assemble()} and {@code getResultItem()} both
 *       return {@code ItemStack.EMPTY}. Must call {@code getResult(tile)} to
 *       read the {@code output} field (which is a shared instance and must be
 *       copied before use).</li>
 *   <li>{@code EnchantingApparatusRecipe} has four NBT-transforming subclasses.
 *       Classification uses the recipe type registry ID so the two supported
 *       item transformations are admitted without accidentally including
 *       spell writing or reactive enchantment.</li>
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
        } else if (ArsRecipeClassifier.TYPE_APPARATUS.equals(typeId)) {
            return getApparatusResult(recipe);
        } else if (ArsRecipeClassifier.TYPE_ENCHANTMENT.equals(typeId)) {
            return ArsDynamicApparatusRecipe.canonicalEnchantmentOutput(recipe);
        } else if (ArsRecipeClassifier.TYPE_ARMOR_UPGRADE.equals(typeId)) {
            return ItemStack.EMPTY;
        } else if (ArsRecipeClassifier.isGlyph(typeId)) {
            return Reflect.<ItemStack>getField(recipe, "output")
                    .map(ItemStack::copy)
                    .orElse(ItemStack.EMPTY);
        }

        RSIntegrationMod.LOGGER.warn(
                "ArsNouveauRecipeHandler: unexpected recipe type requested for result {}", typeId);
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        String typeId = ArsTileAccess.recipeTypeId(recipe);

        if (ArsRecipeClassifier.isImbuement(typeId)) {
            return getImbuementIngredients(recipe);
        } else if (ArsRecipeClassifier.TYPE_APPARATUS.equals(typeId)) {
            return getApparatusIngredients(recipe);
        } else if (ArsRecipeClassifier.TYPE_ENCHANTMENT.equals(typeId)) {
            List<IngredientSpec> specs = ArsDynamicApparatusRecipe.buildMaterials(recipe, null);
            return specs.isEmpty() ? null : specs;
        } else if (ArsRecipeClassifier.isGlyph(typeId)) {
            List<Ingredient> inputs = Reflect.<List<Ingredient>>getField(recipe, "inputs")
                    .orElse(List.of());
            List<IngredientSpec> specs = ArsGlyphMaterials.build(inputs);
            return specs.isEmpty() ? null : specs;
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
        Ingredient input = Reflect.<Ingredient>getField(recipe, "input").orElse(Ingredient.EMPTY);
        List<Ingredient> pedestalItems = Reflect.<List<Ingredient>>getField(recipe, "pedestalItems")
                .orElse(List.of());
        List<IngredientSpec> specs = ArsImbuementMaterials.build(input, pedestalItems);

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
        Ingredient reagent = Reflect.<Ingredient>getField(recipe, "reagent").orElse(Ingredient.EMPTY);
        List<Ingredient> pedestalItems = Reflect.<List<Ingredient>>getField(recipe, "pedestalItems")
                .orElse(List.of());
        List<IngredientSpec> specs = ArsApparatusMaterials.build(reagent, pedestalItems);

        return specs.isEmpty() ? null : specs;
    }
}
