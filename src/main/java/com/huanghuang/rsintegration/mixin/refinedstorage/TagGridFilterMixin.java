package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.mods.rs.RSGridSearchCache;
import com.refinedmods.refinedstorage.screen.grid.filtering.TagGridFilter;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(value = TagGridFilter.class, remap = false)
public class TagGridFilterMixin {

    @Shadow @Final
    private String tagName;

    @Inject(method = "test(Lcom/refinedmods/refinedstorage/screen/grid/stack/IGridStack;)Z",
            at = @At("HEAD"), cancellable = true)
    private void rsi$cachedTagTest(IGridStack stack, CallbackInfoReturnable<Boolean> cir) {
        UUID id = stack.getId();
        String cached = RSGridSearchCache.getTags(id, stack);
        cir.setReturnValue(cached.contains(this.tagName));
    }
}
