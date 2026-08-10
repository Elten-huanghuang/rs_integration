package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.mods.jei.SolCarrotJeiSearchResults;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.ElementSearchLowMem;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Mixin(value = ElementSearchLowMem.class, remap = false)
public abstract class ElementSearchLowMemSolCarrotMixin {
    @Shadow
    @Final
    private List<IListElementInfo<?>> elementInfoList;

    @Inject(method = "getSearchResults", at = @At("HEAD"), cancellable = true)
    private void rsi$searchCurrentSolCarrotStatus(
            ElementPrefixParser.TokenInfo tokenInfo,
            CallbackInfoReturnable<Set<IListElement<?>>> cir) {
        var query = SolCarrotJeiSearchResults.query(tokenInfo);
        if (query != null) {
            List<IListElement<?>> elements = new ArrayList<>(elementInfoList.size());
            for (IListElementInfo<?> info : elementInfoList) {
                elements.add(info.getElement());
            }
            cir.setReturnValue(SolCarrotJeiSearchResults.filter(this, elements, query));
        }
    }
}
