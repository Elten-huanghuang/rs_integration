package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.mods.rs.RSGridSearchCache;
import com.refinedmods.refinedstorage.screen.grid.filtering.ModGridFilter;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(value = ModGridFilter.class, remap = false)
public class ModGridFilterMixin {

    @Shadow @Final
    private String inputModName;

    @Inject(method = "test(Lcom/refinedmods/refinedstorage/screen/grid/stack/IGridStack;)Z",
            at = @At("HEAD"), cancellable = true)
    private void rsi$cachedModTest(IGridStack stack, CallbackInfoReturnable<Boolean> cir) {
        UUID id = stack.getId();
        String cached = RSGridSearchCache.getMod(id, stack);
        cir.setReturnValue(cached.contains(this.inputModName));
    }
}
