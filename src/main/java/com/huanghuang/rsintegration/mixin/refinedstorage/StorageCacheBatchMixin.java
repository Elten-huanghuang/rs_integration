package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.storage.rs.StorageCacheChangeBatch;
import com.refinedmods.refinedstorage.apiimpl.storage.cache.ItemStorageCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** 批量入库期间只累计缓存，结束时再发一条终端增量。 */
@Mixin(value = ItemStorageCache.class, remap = false)
public abstract class StorageCacheBatchMixin {
    @ModifyVariable(method = "add(Lnet/minecraft/world/item/ItemStack;IZZ)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private boolean rsi$deferGridNotification(boolean batched) {
        return batched || StorageCacheChangeBatch.defer(this);
    }
}
