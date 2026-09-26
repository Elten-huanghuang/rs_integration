package com.huanghuang.rsintegration.mixin.jei;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.mods.jei.SolCarrotJeiSearchResults;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.ElementSearch;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Set;

@Mixin(value = ElementSearch.class, remap = false)
public abstract class ElementSearchSolCarrotMixin {
    @Shadow
    @Final
    private Map<Object, IListElement<?>> allElements;

    @Inject(method = "getSearchResults", at = @At("HEAD"), cancellable = true)
    private void rsi$searchCurrentSolCarrotStatus(
            ElementPrefixParser.TokenInfo tokenInfo,
            CallbackInfoReturnable<Set<IListElement<?>>> cir) {
        try {
            var query = SolCarrotJeiSearchResults.query(tokenInfo);
            if (query != null) {
                cir.setReturnValue(SolCarrotJeiSearchResults.filter(
                        this, allElements.values(), query));
            }
        } catch (LinkageError ignored) {
            // SolCarrot search is optional; leave JEI's normal search active
            // when an old/incomplete runtime cannot resolve the helper.
        }
    }
}
