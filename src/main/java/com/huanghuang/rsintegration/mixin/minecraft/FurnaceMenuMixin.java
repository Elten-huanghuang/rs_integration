package com.huanghuang.rsintegration.mixin.minecraft;

import com.huanghuang.rsintegration.network.gui.RemoteGuiAuth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractFurnaceMenu.class)
public abstract class FurnaceMenuMixin {

    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void rsi$bypassDistanceForRemoteGui(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (RemoteGuiAuth.hasActiveAuthorization(player.getUUID())) {
            cir.setReturnValue(true);
        }
    }
}
