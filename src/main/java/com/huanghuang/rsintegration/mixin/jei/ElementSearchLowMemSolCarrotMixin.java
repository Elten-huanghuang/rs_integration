package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.mods.jei.SolCarrotJeiSearchResults;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.ElementSearchLowMem;
import mezz.jei.gui.search.IElementSearch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(value = ElementSearchLowMem.class, remap = false)
public abstract class ElementSearchLowMemSolCarrotMixin {
    // 与 ElementSearch 版本保持一致：只依赖 IElementSearch 接口，
    // 不再 @Shadow elementInfoList 之类的内部字段，避免 JEI 改字段类型后硬崩。
    @Inject(method = "getSearchResults", at = @At("HEAD"), cancellable = true)
    private void rsi$searchCurrentSolCarrotStatus(
            ElementPrefixParser.TokenInfo tokenInfo,
            CallbackInfoReturnable<Set<IListElement<?>>> cir) {
        try {
            var query = SolCarrotJeiSearchResults.query(tokenInfo);
            if (query != null) {
                cir.setReturnValue(SolCarrotJeiSearchResults.filter(
                        this, ((IElementSearch) this).getAllIngredients(), query));
            }
        } catch (LinkageError | RuntimeException ignored) {
            // SolCarrot 搜索属于可选功能；运行时接口不匹配时保持 JEI 原生搜索。
        }
    }
}
