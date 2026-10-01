package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.refinedmods.refinedstorage.apiimpl.storage.cache.listener.FluidGridStorageCacheListener;
import com.refinedmods.refinedstorage.apiimpl.storage.cache.listener.ItemGridStorageCacheListener;
import com.refinedmods.refinedstorage.apiimpl.storage.cache.listener.PortableFluidGridStorageCacheListener;
import com.refinedmods.refinedstorage.apiimpl.storage.cache.listener.PortableItemGridStorageCacheListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = {ItemGridStorageCacheListener.class, FluidGridStorageCacheListener.class,
        PortableItemGridStorageCacheListener.class, PortableFluidGridStorageCacheListener.class}, remap = false)
public abstract class GridSnapshotRefreshMixin {
    @Shadow(remap = false) public abstract void onAttached();

    @Inject(method = "onInvalidated", at = @At("TAIL"))
    private void rsi$refreshRebuiltInventory(CallbackInfo ci) {
        // 复用原版快照和权限路径。每次重建可能换 UUID，不能按 tick 丢弃后一次快照。
        if (RSStorageConfig.enabled(RSStorageConfig.REFRESH_OPEN_GRIDS)) onAttached();
    }
}
