package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.rs.IndexedStackList;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskRoot;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.refinedmods.refinedstorage.api.storage.IStorage;
import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.cache.InvalidateCause;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.util.IStackList;
import com.refinedmods.refinedstorage.apiimpl.storage.cache.ItemStorageCache;
import com.refinedmods.refinedstorage.apiimpl.storage.cache.FluidStorageCache;
import com.refinedmods.refinedstorage.apiimpl.util.ItemStackList;
import com.refinedmods.refinedstorage.apiimpl.util.FluidStackList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

@Mixin(value = {ItemStorageCache.class, FluidStorageCache.class}, remap = false)
public abstract class UnifiedDiskStorageCacheMixin {
    @Shadow(remap = false) @Final @Mutable private IStackList<Object> list;
    @Unique private boolean rsi$rebuilding;

    @Inject(method = "invalidate", at = @At("HEAD"), require = 1)
    private void rsi$begin(InvalidateCause cause, CallbackInfo ci) { rsi$rebuilding = true; }
    @Inject(method = "invalidate", at = @At("RETURN"), require = 1)
    private void rsi$end(InvalidateCause cause, CallbackInfo ci) {
        rsi$rebuilding = false;
        if (list instanceof IndexedStackList<?> indexed) indexed.endRebuild();
    }
    @SuppressWarnings("unchecked")
    @Inject(method = "sort", at = @At("RETURN"), require = 1)
    private void rsi$selectIndex(CallbackInfo ci) {
        IStorageCache<Object> cache = (IStorageCache<Object>) (Object) this;
        boolean unified = cache.getStorages().stream().anyMatch(storage -> storage instanceof IStorageDisk<?> disk
                && UnifiedDiskRoot.FACTORY_ID.equals(disk.getFactoryId()));
        if (unified && !(list instanceof IndexedStackList<?>)) {
            IndexedStackList<Object> indexed = new IndexedStackList<>((Object) this instanceof ItemStorageCache ? FrozenKey.Kind.ITEM : FrozenKey.Kind.FLUID, cache::getStorages);
            if (!rsi$rebuilding) {
                indexed.beginRebuild();
                for (IStorage<Object> storage : cache.getStorages()) {
                    if (storage.getAccessType() == AccessType.INSERT) continue;
                    indexed.sourceForRebuild(storage);
                    for (Object stack : storage.getStacks()) indexed.add(stack);
                }
                indexed.endRebuild();
            }
            list = indexed;
        } else if (!unified && list instanceof IndexedStackList<?>) {
            IStackList<?> original = (Object) this instanceof ItemStorageCache ? new ItemStackList() : new FluidStackList();
            for (var entry : list.getStacks()) ((IStackList<Object>) original).add(entry.getStack());
            list = (IStackList<Object>) original;
        }
        if (rsi$rebuilding && list instanceof IndexedStackList<?> indexed) indexed.beginRebuild();
    }
    @WrapOperation(method = "invalidate", at = @At(value = "INVOKE", target =
            "Lcom/refinedmods/refinedstorage/api/storage/IStorage;getStacks()Ljava/util/Collection;"), require = 1)
    private Collection<?> rsi$source(IStorage<?> storage, Operation<Collection<?>> original) {
        if (list instanceof IndexedStackList<?> indexed) indexed.sourceForRebuild(storage);
        return original.call(storage);
    }
}
