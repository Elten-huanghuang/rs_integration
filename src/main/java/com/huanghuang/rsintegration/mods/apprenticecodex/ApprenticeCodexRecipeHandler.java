package com.huanghuang.rsintegration.mods.apprenticecodex;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class ApprenticeCodexRecipeHandler implements ModRecipeHandler {
    private final boolean essenceSmoker;
    private final String recipeClass;
    private final String typeId;

    static ApprenticeCodexRecipeHandler essenceSmoker() {
        return new ApprenticeCodexRecipeHandler(true, ApprenticeCodexRSModule.ESSENCE_RECIPE,
                ApprenticeCodexRSModule.ESSENCE_SMOKER_TYPE);
    }

    static ApprenticeCodexRecipeHandler spellcasterWorkbench() {
        return new ApprenticeCodexRecipeHandler(false, ApprenticeCodexRSModule.WORKBENCH_RECIPE,
                ApprenticeCodexRSModule.SPELLCASTER_WORKBENCH_TYPE);
    }

    private ApprenticeCodexRecipeHandler(boolean essenceSmoker, String recipeClass, String typeId) {
        this.essenceSmoker = essenceSmoker;
        this.recipeClass = recipeClass;
        this.typeId = typeId;
    }

    @Override public ModType modType() { return ModType.byId(typeId); }
    @Override public boolean canHandle(Recipe<?> recipe) { return recipe.getClass().getName().equals(recipeClass); }
    @Override public boolean preferHandlerIngredients() { return true; }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        Object value = invoke(recipe, essenceSmoker ? "getResultTemplate" : "getPrimaryResultTemplate");
        return value instanceof ItemStack stack ? stack.copy() : ItemStack.EMPTY;
    }

    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        if (essenceSmoker) {
            Object catalyst = invoke(recipe, "getCatalyst");
            Object material = invoke(recipe, "getMaterial");
            if (!(catalyst instanceof Ingredient c) || !(material instanceof Ingredient m)) return null;
            return List.of(new IngredientSpec(c, 1), new IngredientSpec(m, 1));
        }
        Object raw = invoke(recipe, "getSizedIngredients");
        if (!(raw instanceof List<?> sized) || sized.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>(sized.size());
        for (Object entry : sized) {
            Object ingredient = invoke(entry, "ingredient");
            Object count = invoke(entry, "count");
            if (!(ingredient instanceof Ingredient ing) || !(count instanceof Number n) || n.intValue() <= 0) {
                return null;
            }
            specs.add(new IngredientSpec(ing, n.intValue()));
        }
        return List.copyOf(specs);
    }

    @Override
    public List<ItemStack> getSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        if (essenceSmoker) return List.of();
        Object raw = invoke(recipe, "getResultTemplates");
        if (!(raw instanceof List<?> results) || results.size() <= 1) return List.of();
        List<ItemStack> copies = new ArrayList<>();
        for (int i = 1; i < results.size(); i++) {
            if (results.get(i) instanceof ItemStack stack && !stack.isEmpty()) copies.add(stack.copy());
        }
        return List.copyOf(copies);
    }

    @Override
    public boolean hasRuntimeDependentPrimaryNbt(Recipe<?> recipe) {
        return !essenceSmoker;
    }

    private static Object invoke(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (ReflectiveOperationException | LinkageError e) {
            RSIntegrationMod.LOGGER.warn("[RSI-ApprenticeCodex] Cannot read {} from {}",
                    name, target.getClass().getName(), e);
            return null;
        }
    }
}
