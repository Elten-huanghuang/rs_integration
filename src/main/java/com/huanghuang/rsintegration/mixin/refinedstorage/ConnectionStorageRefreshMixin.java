package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.huanghuang.rsintegration.storage.rs.ConnectionRebuildScope;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.apiimpl.network.NetworkNodeGraph;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;
import java.util.function.Consumer;

@Mixin(value = NetworkNodeGraph.class, remap = false)
public abstract class ConnectionStorageRefreshMixin {
    @Shadow @Final private INetwork network;

    @WrapOperation(method = "invalidate", at = @At(value = "INVOKE",
            target = "Ljava/util/Set;forEach(Ljava/util/function/Consumer;)V"))
    private void rsi$groupStorageRefreshes(Set<Consumer<INetwork>> actions,
                                         Consumer<Consumer<INetwork>> consumer, Operation<Void> original) {
        if (!RSStorageConfig.enabled(RSStorageConfig.MERGE_CONNECTION_REBUILDS)) {
            original.call(actions, consumer);
            return;
        }
        ConnectionRebuildScope.execute(network.getItemStorageCache(), network.getFluidStorageCache(),
                () -> original.call(actions, consumer));
    }
}
