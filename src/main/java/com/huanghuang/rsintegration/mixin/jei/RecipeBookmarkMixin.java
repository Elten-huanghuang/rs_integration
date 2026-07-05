package com.huanghuang.rsintegration.mixin.jei;

import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.Field;

@Mixin(value = RecipeBookmark.class, remap = false)
public class RecipeBookmarkMixin {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "create",
              at = @At(value = "INVOKE",
                       target = "Lmezz/jei/api/recipe/category/IRecipeCategory;getRegistryName(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;"),
              remap = false)
    private static ResourceLocation rsIntegration$patchGetRegistryName(
            IRecipeCategory category, Object recipe) {
        ResourceLocation result = category.getRegistryName(recipe);
        if (result != null) return result;

        // mod wrapper with injected recipeId (Touhou Little Maid, Forbidden Arcanus, etc.)
        try {
            Field f = recipe.getClass().getField("rsIntegration$recipeId");
            result = (ResourceLocation) f.get(recipe);
            if (result != null) return result;
        } catch (Exception ignored) {}

        // universal fallback: pseudo-ID so the bookmark works in-session.
        // Serialization won't survive a restart, but the bookmark button
        // renders correctly and the recipe can be re-viewed during the session.
        ResourceLocation typeUid = category.getRecipeType().getUid();
        return new ResourceLocation(typeUid.getNamespace(),
                "rsi_bookmark/" + typeUid.getPath() + "/" + System.identityHashCode(recipe));
    }
}
