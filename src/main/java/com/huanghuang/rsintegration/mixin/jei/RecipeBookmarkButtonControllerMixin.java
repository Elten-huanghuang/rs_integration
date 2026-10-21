package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.plan.ClientRecipeExclusions;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** JEI 15.74+ 收藏按钮控制器上的 RSI 红色递归禁用三态。 */
@Mixin(targets = "mezz.jei.gui.recipes.RecipeBookmarkButtonController", remap = false)
public abstract class RecipeBookmarkButtonControllerMixin {

    @Shadow
    @Final
    private BookmarkList bookmarks;

    @Shadow
    @Final
    private IBookmark recipeBookmark;

    @Shadow
    private boolean bookmarked;

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void rsIntegration$handleBookmarkPress(
            IJeiUserInput input, CallbackInfoReturnable<Boolean> cir) {
        if (!isRightClick(input)) return;
        if (input.isSimulate()) {
            cir.setReturnValue(true);
            return;
        }
        ResourceLocation recipeId = rsi$recipeId();
        if (!ClientRecipeExclusions.isStableRecipeId(recipeId)) {
            cir.setReturnValue(true);
            return;
        }
        if (!bookmarked) {
            if (!bookmarks.add(recipeBookmark)) {
                cir.setReturnValue(true);
                return;
            }
            if (!ClientRecipeExclusions.exclude(recipeId)) {
                bookmarks.remove(recipeBookmark);
            }
            bookmarked = bookmarks.contains(recipeBookmark);
        } else if (!ClientRecipeExclusions.toggle(recipeId)) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-JEI-Bookmark] 无法保存递归禁用偏好 recipe={}", recipeId);
        }
        cir.setReturnValue(true);
    }

    @Inject(method = "onPress", at = @At("HEAD"))
    private void rsIntegration$clearExclusionOnUnbookmark(
            IJeiUserInput input, CallbackInfoReturnable<Boolean> cir) {
        if (isRightClick(input) || input.isSimulate() || !bookmarked) return;
        ClientRecipeExclusions.clear(rsi$recipeId());
    }

    @Inject(method = "getTooltips", at = @At("TAIL"))
    private void rsIntegration$extendTooltip(
            ITooltipBuilder tooltip, CallbackInfo ci) {
        ResourceLocation recipeId = rsi$recipeId();
        if (!ClientRecipeExclusions.isStableRecipeId(recipeId)) {
            tooltip.add(Component.translatable(
                    "rsi.jei.bookmark.tooltip.unavailable"));
            return;
        }
        String key;
        if (!bookmarked) {
            key = "rsi.jei.bookmark.tooltip.add_and_exclude";
        } else if (ClientRecipeExclusions.isExcluded(recipeId)) {
            key = "rsi.jei.bookmark.tooltip.restore";
        } else {
            key = "rsi.jei.bookmark.tooltip.exclude";
        }
        tooltip.add(Component.translatable(key));
    }

    @Inject(method = "drawExtras", at = @At("TAIL"))
    private void rsIntegration$drawExclusion(
            GuiGraphics graphics, Rect2i area, int mouseX, int mouseY,
            float partialTick, CallbackInfo ci) {
        if (!bookmarked || !ClientRecipeExclusions.isExcluded(rsi$recipeId())) return;
        graphics.fill(area.getX(), area.getY(),
                area.getX() + area.getWidth(), area.getY() + area.getHeight(),
                0x33FF0000);
    }

    private ResourceLocation rsi$recipeId() {
        return recipeBookmark instanceof RecipeBookmark<?, ?> bookmark
                ? bookmark.getRecipeUid() : null;
    }

    private static boolean isRightClick(IJeiUserInput input) {
        return input != null && input.getKey() != null
                && input.getKey().getType() == InputConstants.Type.MOUSE
                && input.getKey().getValue() == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
    }
}
