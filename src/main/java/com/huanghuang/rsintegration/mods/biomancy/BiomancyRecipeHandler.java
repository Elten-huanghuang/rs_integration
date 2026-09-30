package com.huanghuang.rsintegration.mods.biomancy;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class BiomancyRecipeHandler implements ModRecipeHandler {

    enum Kind {
        DIGESTER("DigestingRecipe"),
        BIO_LAB("BioBrewingRecipe"),
        DECOMPOSER("DecomposingRecipe"),
        BIO_FORGE("BioForgingRecipe");

        private final String recipeClassName;

        Kind(String recipeClassName) {
            this.recipeClassName = recipeClassName;
        }
    }

    private final String typeId;
    private final Kind kind;

    BiomancyRecipeHandler(String typeId, Kind kind) {
        this.typeId = typeId;
        this.kind = kind;
    }

    @Override
    public ModType modType() {
        return ModType.byId(typeId);
    }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return matchesKind(recipe, kind);
    }

    static boolean matchesKind(Recipe<?> recipe, Kind kind) {
        try {
            Class<?> recipeType = Class.forName(
                    "com.github.elenterius.biomancy.crafting.recipe." + kind.recipeClassName);
            return recipeType.isInstance(recipe);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    @Override
    public boolean preferHandlerIngredients() {
        return true;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        if (kind == Kind.DECOMPOSER) {
            Object outputs = invoke(recipe, "getOutputs");
            if (outputs instanceof List<?> list) {
                for (Object output : list) {
                    ItemStack guaranteed = guaranteedOutput(output);
                    if (!guaranteed.isEmpty()) return guaranteed;
                }
            }
            return ItemStack.EMPTY;
        }
        return recipe.getResultItem(access).copy();
    }

    static ItemStack guaranteedOutput(Object output) {
        Object range = invoke(output, "getCountRange");
        if (range == null) return ItemStack.EMPTY;
        Object minimum = switch (range.getClass().getSimpleName()) {
            case "ConstantValue" -> invoke(range, "value");
            case "UniformRange" -> invoke(range, "min");
            default -> null;
        };
        if (!(minimum instanceof Number number) || number.intValue() <= 0) return ItemStack.EMPTY;
        Object stack = invoke(output, "getItemStack");
        return stack instanceof ItemStack itemStack && !itemStack.isEmpty()
                ? itemStack.copyWithCount(number.intValue()) : ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<IngredientSpec> ingredients = new ArrayList<>();
        switch (kind) {
            case DIGESTER -> addIngredient(ingredients, invoke(recipe, "getIngredient"), 1);
            case BIO_LAB, BIO_FORGE -> {
                Object quantities = invoke(recipe, "getIngredientQuantities");
                if (quantities instanceof Iterable<?> values) {
                    for (Object value : values) addIngredientStack(ingredients, value);
                }
                if (kind == Kind.BIO_LAB) {
                    Object reactant = invoke(recipe, "getReactant");
                    if (reactant instanceof Ingredient ingredient) {
                        addIngredient(ingredients, ingredient, 1);
                    } else if (reactant != null) {
                        addIngredientStack(ingredients, reactant);
                    }
                }
            }
            case DECOMPOSER -> addIngredientStack(
                    ingredients, invoke(recipe, "getIngredientQuantity"));
        }
        return ingredients.isEmpty() ? null : List.copyOf(ingredients);
    }

    @Override
    public boolean hasDeterministicPrimaryOutput(Recipe<?> recipe) {
        return kind != Kind.DECOMPOSER;
    }

    private static void addIngredientStack(List<IngredientSpec> result, @Nullable Object value) {
        if (value == null) return;
        Object ingredient = invoke(value, "ingredient");
        Object count = invoke(value, "count");
        addIngredient(result, ingredient, count instanceof Number number ? number.intValue() : 1);
    }

    private static void addIngredient(List<IngredientSpec> result, @Nullable Object value, int count) {
        if (value instanceof Ingredient ingredient && !ingredient.isEmpty() && count > 0) {
            result.add(new IngredientSpec(ingredient, count));
        }
    }

    @Nullable
    private static Object invoke(Object receiver, String methodName) {
        if (receiver == null) return null;
        try {
            Method method = receiver.getClass().getMethod(methodName);
            return method.invoke(receiver);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
