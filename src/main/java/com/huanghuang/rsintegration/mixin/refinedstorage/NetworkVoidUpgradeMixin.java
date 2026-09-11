package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.voidupgrade.VoidUpgradeManager;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.apiimpl.network.Network;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Network.class, remap = false)
public abstract class NetworkVoidUpgradeMixin {
    @Inject(method = "update", at = @At("TAIL"), remap = false)
    private void rsi$tickVoidUpgrade(CallbackInfo ci) {
        VoidUpgradeManager.tick((INetwork) this);
    }

    @Inject(method = "onRemoved", at = @At("HEAD"), remap = false)
    private void rsi$removeVoidUpgrade(CallbackInfo ci) {
        VoidUpgradeManager.remove((INetwork) this);
    }
}
