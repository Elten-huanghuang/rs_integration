package com.huanghuang.rsintegration.mixin.farmandcharm;

import com.huanghuang.rsintegration.mods.farmandcharm.client.NativeRecipeBookCrafting;
import com.huanghuang.rsintegration.mods.farmandcharm.client.NativeRecipeBookSelection;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Remembers the recipe selected in the shared Let's Do recipe-book widget. */
@Mixin(targets = "de.cristelknight.doapi.client.recipebook.screen.widgets.PrivateRecipeBookWidget",
        remap = false)
@Pseudo
public abstract class NativeRecipeBookWidgetMixin implements NativeRecipeBookSelection {
    @Unique
    private Recipe<?> rsi$selectedRecipe;

    @Inject(method = "m_6375_", at = @At("RETURN"), remap = false)
    private void rsi$rememberSelection(double mouseX, double mouseY, int button,
                                        CallbackInfoReturnable<Boolean> callback) {
        Object area = rsi$readField("recipesArea");
        if (area == null) return;
        try {
            Method getter = area.getClass().getMethod("getLastClickedRecipe");
            Object recipe = getter.invoke(area);
            if (recipe instanceof Recipe<?> selected
                    && NativeRecipeBookCrafting.isSupportedRecipe(selected)) {
                rsi$selectedRecipe = selected;
            }
        } catch (ReflectiveOperationException ignored) {
            // DOAPI changed its private field shape; the button remains harmless.
        }
    }

    @Inject(method = "reset", at = @At("HEAD"), remap = false)
    private void rsi$clearSelection(CallbackInfo callback) {
        rsi$selectedRecipe = null;
    }

    @Override
    public Recipe<?> rsi$getSelectedRecipe() {
        return rsi$selectedRecipe;
    }

    @Unique
    private Object rsi$readField(String name) {
        Class<?> type = getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(this);
            } catch (ReflectiveOperationException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }
}
