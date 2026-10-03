package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.mods.sophisticatedbackpacks.RSMagnetFluidGhostHandler;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraftforge.fluids.FluidStack;
import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.compat.jei.StorageGhostIngredientHandler", remap = false)
public abstract class StorageGhostIngredientHandlerMixin {
    @Inject(method = "getTargetsTyped(Lnet/p3pp3rf1y/sophisticatedcore/client/gui/StorageScreenBase;Lmezz/jei/api/ingredients/ITypedIngredient;Z)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true)
    private <I> void rsi$fluidTargets(StorageScreenBase<?> screen, ITypedIngredient<I> ingredient,
            boolean start, CallbackInfoReturnable<List<IGhostIngredientHandler.Target<I>>> cir) {
        if (ingredient.getIngredient() instanceof FluidStack fluid) {
            cir.setReturnValue(RSMagnetFluidGhostHandler.targets(screen, fluid));
        }
    }
}
