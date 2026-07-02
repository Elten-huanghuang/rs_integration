package com.huanghuang.rsintegration.mixin.crockpot;

import com.sihenzhang.crockpot.block.entity.CrockPotBlockEntity;
import com.sihenzhang.crockpot.inventory.CrockPotMenu;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Guards {@code getBurningProgress()} against null {@code blockEntity}
 * that occurs when the menu is opened remotely — the client-side menu
 * has no direct reference to the server's block entity.
 */
@Mixin(CrockPotMenu.class)
public abstract class CrockPotMenuMixin {

    @Shadow(remap = false)
    private CrockPotBlockEntity blockEntity;

    @Inject(method = "getCookingProgress", at = @At("HEAD"), cancellable = true, remap = false)
    public void rsi$safeGetCookingProgress(CallbackInfoReturnable<Integer> cir) {
        if (this.blockEntity == null) {
            cir.setReturnValue(0);
        }
    }

    // 顺便把 getBurningProgress 也防御一下，防止接下来又崩这里
    @Inject(method = "getBurningProgress", at = @At("HEAD"), cancellable = true, remap = false)
    public void rsi$safeGetBurningProgress(CallbackInfoReturnable<Integer> cir) {
        if (this.blockEntity == null) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "m_6877_", at = @At("HEAD"), cancellable = true, remap = false)
    public void rsi$safeRemoved(Player player, CallbackInfo ci) {
        if (this.blockEntity == null) {
            ci.cancel();
        }
    }

    // When opened remotely the client-side menu has no BlockEntity, which
    // can NPE in stillValid() → force-closing the screen and desyncing the
    // player inventory.  Return true to keep the remote GUI alive.
    @Inject(method = "m_6875_", at = @At("HEAD"), cancellable = true, remap = false)
    public void rsi$safeStillValid(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (this.blockEntity == null) {
            cir.setReturnValue(true);
        }
    }
}