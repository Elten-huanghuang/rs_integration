package com.huanghuang.rsintegration.mixin.jei;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.mods.jei.JeiIngredientFilterRefresh;
import com.huanghuang.rsintegration.mods.jei.SolCarrotJeiSearchRefresh;
import mezz.jei.gui.ingredients.IngredientFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IngredientFilter.class, remap = false)
public abstract class IngredientFilterSolCarrotMixin implements JeiIngredientFilterRefresh {
    @Shadow
    public abstract void invalidateCache();

    @Invoker("notifyListenersOfChange")
    protected abstract void rsi$notifyListenersOfChange();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rsi$registerSolCarrotRefresh(CallbackInfo ci) {
        SolCarrotJeiSearchRefresh.register((JeiIngredientFilterRefresh) this);
    }

    @Override
    public void rsi$refreshSolCarrotResults() {
        invalidateCache();
        rsi$notifyListenersOfChange();
    }
}
