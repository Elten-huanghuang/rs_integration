package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskAbilities;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskAbilityService;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

/** Virtualizes deterministic Malum Favor of the Void conversions. */
public final class MalumVoidFavorVirtualRecipeHandler implements ModRecipeHandler {

    private static final String RECIPE_CLASS =
            "com.sammy.malum.common.recipe.FavorOfTheVoidRecipe";

    @Nonnull
    @Override
    public ModType modType() {
        return ModType.byId(MalumRSModule.VOID_FAVOR_TYPE_ID);
    }

    @Override
    public boolean canHandle(@Nonnull Recipe<?> recipe) {
        return isSupported(recipe);
    }

    @Override
    public boolean cacheByRecipeClass() {
        return false;
    }

    @Override
    public boolean isAvailableForPlanning(@Nonnull Recipe<?> recipe,
                                          @Nullable ServerPlayer player) {
        return isSupported(recipe) && player != null
                && ResonanceDiskAbilityService.hasActiveAbility(
                        player, ResonanceDiskAbilities.MALUM_VOID_FAVOR);
    }

    @Nonnull
    @Override
    public ItemStack getResultItem(@Nonnull Recipe<?> recipe,
                                   @Nonnull RegistryAccess access) {
        RecipeData data = readSupported(recipe);
        return data == null ? ItemStack.EMPTY : data.output().copy();
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        RecipeData data = readSupported(recipe);
        if (data == null) return null;
        ItemStack[] candidates = withoutIdentityOutput(data.input().getItems(), data.output());
        if (candidates.length == 0) return null;
        return List.of(new IngredientSpec(Ingredient.of(Arrays.stream(candidates)), 1));
    }

    public static boolean isSupported(Object value) {
        return value instanceof Recipe<?> recipe && readSupported(recipe) != null;
    }

    @Nullable
    private static RecipeData readSupported(Recipe<?> recipe) {
        if (!RECIPE_CLASS.equals(recipe.getClass().getName())) {
            return null;
        }
        Object inputValue = readField(recipe, "input");
        Object outputValue = readField(recipe, "output");
        if (!(inputValue instanceof Ingredient input)
                || !(outputValue instanceof ItemStack output)
                || output.isEmpty()) {
            return null;
        }
        return withoutIdentityOutput(input.getItems(), output).length > 0
                ? new RecipeData(input, output) : null;
    }

    @Nullable
    private static Object readField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Keep searching Malum's recipe base classes.
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    static ItemStack[] withoutIdentityOutput(ItemStack[] candidates, ItemStack output) {
        if (candidates == null || output == null || output.isEmpty()) return new ItemStack[0];
        return Arrays.stream(candidates)
                .filter(candidate -> candidate != null && !candidate.isEmpty())
                .filter(candidate -> !ItemStack.isSameItemSameTags(candidate, output))
                .map(candidate -> candidate.copyWithCount(1))
                .toArray(ItemStack[]::new);
    }

    private record RecipeData(Ingredient input, ItemStack output) {}
}
