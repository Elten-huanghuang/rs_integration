package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.voidupgrade.VoidUpgradeManager;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.apiimpl.network.node.GridNetworkNode;
import com.refinedmods.refinedstorage.apiimpl.network.node.NetworkNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = NetworkNode.class, remap = false)
public abstract class NetworkNodeVoidUpgradeMixin {
    @Inject(method = "onConnected", at = @At("TAIL"), remap = false)
    private void rsi$connectVoidUpgrade(INetwork network, CallbackInfo ci) {
        if ((Object) this instanceof GridNetworkNode grid && !grid.getLevel().isClientSide) {
            VoidUpgradeManager.onGridConnected(grid, network);
        }
    }

    @Inject(method = "onDisconnected", at = @At("TAIL"), remap = false)
    private void rsi$disconnectVoidUpgrade(INetwork network, CallbackInfo ci) {
        if ((Object) this instanceof GridNetworkNode grid && !grid.getLevel().isClientSide) {
            VoidUpgradeManager.onGridDisconnected(network);
        }
    }
}
