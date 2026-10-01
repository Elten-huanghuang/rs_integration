package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.huanghuang.rsintegration.storage.rs.ConnectionRebuildScope;
import com.refinedmods.refinedstorage.api.storage.cache.InvalidateCause;
import com.refinedmods.refinedstorage.apiimpl.storage.cache.FluidStorageCache;
import com.refinedmods.refinedstorage.apiimpl.storage.cache.ItemStorageCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = {ItemStorageCache.class, FluidStorageCache.class}, remap = false)
public abstract class StorageRefreshDeduplicationMixin {
    @Inject(method = "invalidate", at = @At("HEAD"), cancellable = true)
    private void rsi$avoidRepeatedConnectionRefresh(InvalidateCause cause, CallbackInfo ci) {
        if (!RSStorageConfig.enabled(RSStorageConfig.MERGE_CONNECTION_REBUILDS)) return;
        if (cause != InvalidateCause.CONNECTED_STATE_CHANGED) {
            ConnectionRebuildScope.record(this, false);
        } else if (ConnectionRebuildScope.alreadyRebuilt(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "invalidate", at = @At("RETURN"))
    private void rsi$rememberSuccessfulRefresh(InvalidateCause cause, CallbackInfo ci) {
        if (cause == InvalidateCause.CONNECTED_STATE_CHANGED
                && RSStorageConfig.enabled(RSStorageConfig.MERGE_CONNECTION_REBUILDS)) {
            ConnectionRebuildScope.record(this, true);
        }
    }
}
