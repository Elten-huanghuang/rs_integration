package com.huanghuang.rsintegration.mods.lychee;

import com.google.gson.JsonElement;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import snownee.lychee.core.def.BlockPredicateHelper;
import snownee.lychee.core.post.DropItem;
import snownee.lychee.core.post.PostAction;
import snownee.lychee.interaction.BlockInteractingRecipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Safe logical execution for Lychee right-click recipes on a bound hydraulic press. */
public final class LycheeBlockInteractingRecipeHandler implements ModRecipeHandler {
    static final String HYDRAULIC_PRESS = "rustic_engineer:hydraulic_press";

    @Nonnull
    @Override
    public ModType modType() {
        return ModType.byId(LycheeRSModule.BLOCK_INTERACTING_TYPE_ID);
    }

    @Override
    public boolean canHandle(@Nonnull Recipe<?> recipe) {
        return isSupported(recipe);
    }

    @Override
    public boolean cacheByRecipeClass() {
        return false;
    }

    @Nonnull
    @Override
    public ItemStack getResultItem(@Nonnull Recipe<?> recipe, @Nonnull RegistryAccess access) {
        DropItem drop = supportedDrop(recipe);
        return drop == null ? ItemStack.EMPTY : drop.stack.copy();
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> value) {
        if (!isSupported(value)) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ingredient : value.getIngredients()) {
            if (!ingredient.isEmpty()) specs.add(new IngredientSpec(ingredient, 1));
        }
        return specs.isEmpty() ? null : List.copyOf(specs);
    }

    public static boolean isSupported(Object value) {
        return unsupportedReason(value) == null;
    }

    @Nullable
    public static String unsupportedReason(Object value) {
        if (!(value instanceof BlockInteractingRecipe recipe)) {
            return "class=" + (value == null ? "null" : value.getClass().getName());
        }
        // BlockClickingRecipe is a left-click operation and must use a separate executor.
        if (value instanceof snownee.lychee.interaction.BlockClickingRecipe) return "left_click";
        if (!recipe.getConditions().isEmpty()) return "recipe_conditions=" + recipe.getConditions().size();
        JsonElement block = BlockPredicateHelper.toJson(recipe.getBlock());
        if (!LycheeVirtualRecipeHandler.isSupportedSubstrateJson(block, HYDRAULIC_PRESS, false)) {
            return "block_predicate=" + block;
        }
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty() || ingredients.stream().allMatch(Ingredient::isEmpty)) {
            return "ingredients=empty";
        }
        List<PostAction> actions = recipe.getPostActions().toList();
        if (actions.size() != 1 || !(actions.get(0) instanceof DropItem drop)) {
            return "post_actions=" + actions.size();
        }
        if (!drop.getConditions().isEmpty()) return "post_conditions=" + drop.getConditions().size();
        return drop.stack.isEmpty() ? "drop=empty" : null;
    }

    @Nullable
    private static DropItem supportedDrop(Object value) {
        if (!isSupported(value)) return null;
        List<PostAction> actions = ((BlockInteractingRecipe) value).getPostActions().toList();
        return (DropItem) actions.get(0);
    }
}
