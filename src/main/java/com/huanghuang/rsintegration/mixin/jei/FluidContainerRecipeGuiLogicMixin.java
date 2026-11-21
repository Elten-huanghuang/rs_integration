package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.client.recipe.FluidContainerRecipeCategory;
import com.huanghuang.rsintegration.mods.jei.FluidContainerJeiRuntime;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.gui.recipes.RecipeGuiLogic;
import mezz.jei.gui.recipes.lookups.ILookupState;
import mezz.jei.gui.recipes.lookups.IngredientLookupState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

@Mixin(value = RecipeGuiLogic.class, remap = false)
public abstract class FluidContainerRecipeGuiLogicMixin {
    @Shadow @Final private IRecipeManager recipeManager;
    @Shadow @Final private IRecipeTransferManager recipeTransferManager;
    @Shadow private ILookupState state;

    @Unique private final Map<ILookupState, Long> rsi$fluidLookupRevisions = new WeakHashMap<>();
    @Unique private final Map<ILookupState, List<RecipeType<?>>> rsi$fluidLookupLimits = new WeakHashMap<>();

    @Invoker("setState")
    protected abstract boolean rsi$setState(ILookupState newState, boolean addToHistory);

    @Inject(method = "setState", at = @At("RETURN"))
    private void rsi$trackRecipeLookup(ILookupState newState, boolean addToHistory,
                                       CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ() && newState instanceof IngredientLookupState) {
            rsi$fluidLookupRevisions.putIfAbsent(newState, FluidContainerJeiRuntime.completedRevision());
        }
    }

    @Inject(method = "showCategories", at = @At("RETURN"))
    private void rsi$trackRequestedTypes(List<RecipeType<?>> types, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ()) rsi$fluidLookupLimits.put(state, List.copyOf(types));
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void rsi$refreshAfterFluidRegistration(CallbackInfo callback) {
        ILookupState previous = state;
        if (!(previous instanceof IngredientLookupState)) return;

        long revision = FluidContainerJeiRuntime.completedRevision();
        Long previousRevision = rsi$fluidLookupRevisions.get(previous);
        if (previousRevision == null || previousRevision == revision) return;
        rsi$fluidLookupRevisions.put(previous, revision);

        List<RecipeType<?>> limits = rsi$fluidLookupLimits.get(previous);
        if (limits != null && !limits.contains(FluidContainerRecipeCategory.TYPE)) return;
        var focuses = previous.getFocuses();
        var lookup = recipeManager.createRecipeCategoryLookup().limitFocus(focuses.getAllFocuses());
        if (limits != null) lookup.limitTypes(limits);
        List<IRecipeCategory<?>> categories = lookup.get().toList();
        if (categories.isEmpty()) return;
        if (categories.stream().noneMatch(FluidContainerRecipeGuiLogicMixin::rsi$isFluidCategory)
                && previous.getRecipeCategories().stream()
                        .noneMatch(FluidContainerRecipeGuiLogicMixin::rsi$isFluidCategory)) return;

        IRecipeCategory<?> selected = previous.getFocusedRecipes().getRecipeCategory();
        ILookupState refreshed = IngredientLookupState.create(recipeManager, focuses, categories, recipeTransferManager);
        if (refreshed.moveToRecipeCategory(selected)) {
            // 保留当前分类和页码，确保新结果较少时页码仍然有效。
            int recipesPerPage = previous.getRecipesPerPage();
            refreshed.setRecipesPerPage(recipesPerPage);
            int page = Math.min(previous.getRecipeIndex() / recipesPerPage, refreshed.pageCount() - 1);
            for (int i = 0; i < page; i++) refreshed.nextPage();
        }
        if (limits != null) rsi$fluidLookupLimits.put(refreshed, limits);
        rsi$setState(refreshed, false);
    }

    @Unique
    private static boolean rsi$isFluidCategory(IRecipeCategory<?> category) {
        return FluidContainerRecipeCategory.TYPE.equals(category.getRecipeType());
    }
}
