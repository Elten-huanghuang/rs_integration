package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.crafting.plan.ClientRecipeExclusions;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.common.gui.JeiTooltip;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import mezz.jei.gui.elements.GuiIconButton;
import mezz.jei.gui.input.UserInput;
import mezz.jei.gui.recipes.RecipeBookmarkButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** JEI 配方页收藏按钮的第三态：绿色收藏、红色递归禁用。 */
@Mixin(value = RecipeBookmarkButton.class, remap = false)
public abstract class RecipeBookmarkButtonMixin {

    @Shadow
    @Final
    private RecipeBookmark<?, ?> recipeBookmark;

    @Shadow
    private boolean bookmarked;

    @Inject(method = "onMouseClicked", at = @At("HEAD"))
    private void rsIntegration$clearExclusionOnUnbookmark(
            UserInput input, CallbackInfoReturnable<Boolean> cir) {
        if (recipeBookmark == null || input.isSimulate()
                || isMouseButton(input, GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                || !bookmarked) return;
        ClientRecipeExclusions.clear(recipeBookmark.getRecipeUid());
    }

    @Inject(method = "draw", at = @At("TAIL"))
    private void rsIntegration$drawExclusionOverlay(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        if (!bookmarked || recipeBookmark == null
                || !ClientRecipeExclusions.isExcluded(
                recipeBookmark.getRecipeUid())) return;
        GuiIconButton button = ((GuiIconToggleButtonAccessor) this).getButton();
        graphics.fill(button.getX(), button.getY(),
                button.getX() + button.getWidth(),
                button.getY() + button.getHeight(), 0x33FF0000);
    }

    @Inject(method = "getTooltips", at = @At("TAIL"))
    private void rsIntegration$extendBookmarkTooltip(
            JeiTooltip tooltip, CallbackInfo ci) {
        if (recipeBookmark == null) return;
        if (!ClientRecipeExclusions.isStableRecipeId(recipeBookmark.getRecipeUid())) {
            tooltip.add(Component.translatable(
                    "rsi.jei.bookmark.tooltip.unavailable"));
            return;
        }
        String key;
        if (!bookmarked) {
            key = "rsi.jei.bookmark.tooltip.add_and_exclude";
        } else if (ClientRecipeExclusions.isExcluded(recipeBookmark.getRecipeUid())) {
            key = "rsi.jei.bookmark.tooltip.restore";
        } else {
            key = "rsi.jei.bookmark.tooltip.exclude";
        }
        tooltip.add(Component.translatable(key));
    }

    private static boolean isMouseButton(UserInput input, int expected) {
        return input != null && input.getKey() != null
                && input.getKey().getType() == InputConstants.Type.MOUSE
                && input.getKey().getValue() == expected;
    }
}
