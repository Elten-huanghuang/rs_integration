package com.huanghuang.rsintegration.compat.jei;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.plan.ClientRecipeExclusions;
import com.huanghuang.rsintegration.mixin.jei.RecipeBookmarkButtonAccess;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.gui.input.IUserInputHandler;
import mezz.jei.gui.input.UserInput;
import mezz.jei.gui.recipes.RecipeBookmarkButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/** 在 JEI 原有左键输入处理器之前处理书签右键。 */
public final class RecipeBookmarkRightClickHandler implements IUserInputHandler {
    private final RecipeBookmarkButton button;
    private final IUserInputHandler delegate;

    public RecipeBookmarkRightClickHandler(RecipeBookmarkButton button,
                                           IUserInputHandler delegate) {
        this.button = button;
        this.delegate = delegate;
    }

    @Override
    public Optional<IUserInputHandler> handleUserInput(
            Screen screen, UserInput input, IInternalKeyMappings keyMappings) {
        if (!isRightClick(input) || !button.isVisible()
                || !button.isMouseOver(input.getMouseX(), input.getMouseY())) {
            return delegate.handleUserInput(screen, input, keyMappings);
        }
        if (input.isSimulate()) return Optional.of(this);
        RecipeBookmarkButtonAccess access = (RecipeBookmarkButtonAccess) button;
        ResourceLocation recipeId = access.rsi$getRecipeBookmark().getRecipeUid();
        if (!ClientRecipeExclusions.isStableRecipeId(recipeId)) {
            return Optional.of(this);
        }
        if (!access.rsi$isBookmarked()) {
            if (!access.rsi$getBookmarks().add(access.rsi$getRecipeBookmark())) {
                return Optional.of(this);
            }
            if (!ClientRecipeExclusions.exclude(recipeId)) {
                access.rsi$getBookmarks().remove(access.rsi$getRecipeBookmark());
            }
        } else if (!ClientRecipeExclusions.toggle(recipeId)) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-JEI-Bookmark] 无法保存递归禁用偏好 recipe={}", recipeId);
        }
        button.tick();
        return Optional.of(this);
    }

    @Override
    public void unfocus() {
        delegate.unfocus();
    }

    @Override
    public Optional<IUserInputHandler> handleMouseScrolled(
            double mouseX, double mouseY, double scrollDelta) {
        return delegate.handleMouseScrolled(mouseX, mouseY, scrollDelta);
    }

    private static boolean isRightClick(UserInput input) {
        return input != null && input.getKey() != null
                && input.getKey().getType() == InputConstants.Type.MOUSE
                && input.getKey().getValue() == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
    }
}
