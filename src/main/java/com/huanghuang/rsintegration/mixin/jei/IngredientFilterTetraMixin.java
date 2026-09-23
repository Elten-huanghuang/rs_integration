package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.mods.tetra.client.TetraWorkbenchMaterialState;
import com.huanghuang.rsintegration.mods.jei.JeiIngredientFilterRefresh;
import com.huanghuang.rsintegration.mods.jei.TetraWorkbenchJeiFilterRefreshRegistry;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.gui.ingredients.IngredientFilter;
import mezz.jei.gui.overlay.elements.IElement;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Restricts JEI's existing item list to Tetra's current workbench materials. */
@Mixin(value = IngredientFilter.class, remap = false)
public abstract class IngredientFilterTetraMixin {
    private static List<IElement<?>> rsi$tetraSource;
    private static List<IElement<?>> rsi$tetraFiltered = List.of();
    private static long rsi$tetraCandidateVersion = Long.MIN_VALUE;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rsi$registerTetraFilterRefresh(CallbackInfo ci) {
        TetraWorkbenchJeiFilterRefreshRegistry.register((JeiIngredientFilterRefresh) this);
    }

    @Inject(method = "getElements", at = @At("RETURN"), cancellable = true)
    private void rsi$filterTetraItems(CallbackInfoReturnable<List<IElement<?>>> cir) {
        if (!TetraWorkbenchMaterialState.isActive()) {
            rsi$tetraSource = null;
            rsi$tetraFiltered = List.of();
            rsi$tetraCandidateVersion = Long.MIN_VALUE;
            return;
        }
        List<IElement<?>> source = cir.getReturnValue();
        long candidateVersion = TetraWorkbenchMaterialState.getCandidateVersion();
        if (source == rsi$tetraSource && candidateVersion == rsi$tetraCandidateVersion) {
            cir.setReturnValue(rsi$tetraFiltered);
            return;
        }

        List<IElement<?>> filtered = new ArrayList<>();
        for (IElement<?> element : source) {
            ITypedIngredient<?> typed = element.getTypedIngredient();
            if (typed != null && typed.getType() == VanillaTypes.ITEM_STACK
                    && TetraWorkbenchMaterialState.matches(
                    (ItemStack) typed.getIngredient())) {
                filtered.add(element);
            }
        }
        filtered.sort(Comparator.comparing(IElement::getTypedIngredient,
                (left, right) -> TetraWorkbenchMaterialState.compareForJei(
                        (ItemStack) left.getIngredient(), (ItemStack) right.getIngredient())));
        rsi$tetraSource = source;
        rsi$tetraFiltered = List.copyOf(filtered);
        rsi$tetraCandidateVersion = candidateVersion;
        cir.setReturnValue(rsi$tetraFiltered);
    }
}
