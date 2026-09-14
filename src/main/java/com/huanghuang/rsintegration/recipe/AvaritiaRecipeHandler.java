package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.mods.avaritia.CraftingTableBatchDelegate;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class AvaritiaRecipeHandler extends AbstractRecipeHandler {

    private static final String RECIPE_PKG = "committee.nova.mods.avaritia.common.crafting.recipe.";
    private static final int EXTREME_SMITHING_ADDITION_SLOTS = 3;

    static {
        registerRecipePrefixes(AvaritiaRecipeHandler.class, RECIPE_PKG);
    }

    @Override
    public ModType modType() { return ModType.byId(ModIds.ID_AVARITIA_CRAFTING); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        // The neutron compressor integration is intentionally not supported.
        // Keep the broad Avaritia prefix for table/special recipes, but do not
        // let the removed compressor path re-enter through the generic handler.
        return !recipe.getClass().getName().endsWith("CompressorRecipe")
                && super.canHandle(recipe);
    }

    @Override
    public boolean isCompatibleBinding(Recipe<?> recipe, @Nullable String blockKey) {
        int recipeTier = CraftingTableBatchDelegate.recipeTier(recipe);
        if (recipeTier <= 0) return true;
        int machineTier = CraftingTableBatchDelegate.machineTierFromBindingKey(blockKey);
        return machineTier <= 0 || machineTier == recipeTier;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return recipe.getResultItem(access);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        String name = recipe.getClass().getName();
        if (name.endsWith("ExtremeSmithingRecipe")) {
            return getSmithingIngredients(recipe);
        }
        // Avaritia table recipes all implement vanilla getIngredients().
        List<Ingredient> list = recipe.getIngredients();
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : list) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }

    private List<IngredientSpec> getSmithingIngredients(Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();
        try {
            Ingredient template = (Ingredient) field(recipe, "template");
            Ingredient base = (Ingredient) field(recipe, "base");
            Ingredient additions = (Ingredient) field(recipe, "additions");
            if (template != null && !template.isEmpty()) specs.add(new IngredientSpec(template, 1));
            if (base != null && !base.isEmpty()) specs.add(new IngredientSpec(base, 1));
            if (additions != null && !additions.isEmpty()) {
                specs.addAll(getSmithingAdditionSpecs(additions));
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Avaritia] Failed to reflect smithing ingredients", e);
        }
        return specs.isEmpty() ? null : specs;
    }

    /**
     * Re-Avaritia serializes the three positional additions as one vanilla
     * Ingredient. Its runtime matcher consequently treats all three values as
     * alternatives in every slot, while its recipe display presents them as
     * three ordered inputs. Restore that positional contract for planning and
     * extraction when the Ingredient contains exactly three concrete entries.
     */
    static List<IngredientSpec> getSmithingAdditionSpecs(Ingredient additions) {
        ItemStack[] entries = additions.getItems();
        List<IngredientSpec> specs = new ArrayList<>(EXTREME_SMITHING_ADDITION_SLOTS);
        if (entries.length == EXTREME_SMITHING_ADDITION_SLOTS) {
            for (ItemStack entry : entries) {
                if (entry.isEmpty()) return repeatedAdditionSpecs(additions);
                specs.add(new IngredientSpec(Ingredient.of(entry.copyWithCount(1)), 1));
            }
            return specs;
        }
        return repeatedAdditionSpecs(additions);
    }

    private static List<IngredientSpec> repeatedAdditionSpecs(Ingredient additions) {
        List<IngredientSpec> specs = new ArrayList<>(EXTREME_SMITHING_ADDITION_SLOTS);
        for (int i = 0; i < EXTREME_SMITHING_ADDITION_SLOTS; i++) {
            specs.add(new IngredientSpec(additions, 1));
        }
        return specs;
    }

    private static Object field(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }
}
