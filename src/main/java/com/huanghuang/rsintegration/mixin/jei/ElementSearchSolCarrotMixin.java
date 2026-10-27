package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.mods.jei.SolCarrotJeiSearchResults;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.ElementSearch;
import mezz.jei.gui.search.IElementSearch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(value = ElementSearch.class, remap = false)
public abstract class ElementSearchSolCarrotMixin {
    // 元素集合一律通过 IElementSearch#getAllIngredients() 取用。
    // JEI 15.20.0.106 起 allElements 字段先被移除、15.20.0.112 又改回
    // Set<IListElement<?>>、15.20.0.129 才恢复成 Map<Object, IListElement<?>>，
    // 直接 @Shadow 字段会在某个具体版本上抛 InvalidMixinException 导致启动崩溃。
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
