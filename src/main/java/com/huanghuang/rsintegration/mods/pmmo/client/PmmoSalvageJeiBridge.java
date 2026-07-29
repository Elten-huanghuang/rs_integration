package com.huanghuang.rsintegration.mods.pmmo.client;

import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PmmoSalvageJeiBridge {
    private static final Set<String> REGISTERED = new HashSet<>();

    private PmmoSalvageJeiBridge() {}

    public static synchronized void registerRecipes(IRecipeRegistration registration) {
        List<PmmoSalvageRecipe> recipes = PmmoSalvageAccess.recipes();
        REGISTERED.clear();
        REGISTERED.addAll(recipes.stream().map(PmmoSalvageRecipe::key).toList());
        registration.addRecipes(PmmoSalvageRecipeCategory.TYPE, recipes);
    }

    public static synchronized void addLateSyncedRecipes(IJeiRuntime runtime) {
        List<PmmoSalvageRecipe> additions = PmmoSalvageAccess.recipes().stream()
                .filter(recipe -> !REGISTERED.contains(recipe.key()))
                .toList();
        if (additions.isEmpty()) return;
        runtime.getRecipeManager().addRecipes(PmmoSalvageRecipeCategory.TYPE, additions);
        REGISTERED.addAll(additions.stream().map(PmmoSalvageRecipe::key).toList());
    }

    public static synchronized void clear() {
        REGISTERED.clear();
    }
}
