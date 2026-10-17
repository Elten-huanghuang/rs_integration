package com.huanghuang.rsintegration.mixin.farmandcharm;

import com.huanghuang.rsintegration.mods.farmandcharm.client.NativeRecipeBookButton;
import com.huanghuang.rsintegration.mods.farmandcharm.client.NativeRecipeBookButtonHost;
import com.huanghuang.rsintegration.mods.farmandcharm.client.NativeRecipeBookCrafting;
import com.huanghuang.rsintegration.mods.farmandcharm.client.NativeRecipeBookSelection;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the same recipe-book plan button to the Let's Do roaster screen. */
@Mixin(targets = "net.satisfy.farm_and_charm.client.gui.RoasterGui", remap = false)
@Pseudo
public abstract class RoasterGuiMixin implements NativeRecipeBookButtonHost {
    @Unique
    private Button rsi$nativeCraftButton;

    @Inject(method = "m_7856_", at = @At("TAIL"), remap = false)
    private void rsi$addNativeCraftButton(CallbackInfo callback) {
        Screen screen = (Screen) (Object) this;
        rsi$nativeCraftButton = NativeRecipeBookButton.create(screen, this::rsi$requestNativeCraft);
        rsi$addWidget(screen, rsi$nativeCraftButton);
    }

    @Override
    public void rsi$updateNativeCraftButton() {
        if (rsi$nativeCraftButton != null) {
            NativeRecipeBookButton.update((Screen) (Object) this, rsi$nativeCraftButton);
        }
    }

    @Unique
    private void rsi$addWidget(Screen screen, Button button) {
        for (java.lang.reflect.Method method : Screen.class.getDeclaredMethods()) {
            if (method.getParameterCount() != 1
                    || !method.getParameterTypes()[0].isAssignableFrom(button.getClass())) {
                continue;
            }
            String name = method.getName();
            if (!"addRenderableWidget".equals(name) && !"m_142416_".equals(name)) continue;
            try {
                method.setAccessible(true);
                method.invoke(screen, button);
            } catch (ReflectiveOperationException ignored) {
                // The button is optional; a mapping change must not break the GUI.
            }
            return;
        }
    }

    @Unique
    private void rsi$requestNativeCraft() {
        Object recipeBook = NativeRecipeBookButton.recipeBook((Screen) (Object) this);
        if (recipeBook instanceof NativeRecipeBookSelection selection) {
            Recipe<?> recipe = selection.rsi$getSelectedRecipe();
            if (recipe != null) {
                NativeRecipeBookCrafting.request(recipe);
                return;
            }
        }
        NativeRecipeBookCrafting.tellSelectionRequired();
    }

}
