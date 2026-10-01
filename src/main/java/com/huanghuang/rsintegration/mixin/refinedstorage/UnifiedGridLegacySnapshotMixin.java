package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.unifiedgrid.client.UnifiedGridView;
import com.refinedmods.refinedstorage.network.grid.GridItemUpdateMessage;
import com.refinedmods.refinedstorage.network.grid.GridFluidUpdateMessage;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 已建立混合会话后拒绝没有菜单身份的旧全量包，防止其重新创建单类型视图。 */
@Mixin(value = {GridItemUpdateMessage.class, GridFluidUpdateMessage.class}, remap = false)
public abstract class UnifiedGridLegacySnapshotMixin {
    @Inject(method = "lambda$handle$0", at = @At("HEAD"), cancellable = true)
    private static void rsi$ignoreLegacySnapshot(@Coerce Object message, GridScreen screen, CallbackInfo ci) {
        if (screen.getView() instanceof UnifiedGridView) ci.cancel();
    }
}
