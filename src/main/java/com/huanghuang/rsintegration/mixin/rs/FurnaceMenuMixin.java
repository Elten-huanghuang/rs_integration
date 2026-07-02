package com.huanghuang.rsintegration.mixin.rs;

import com.huanghuang.rsintegration.network.RemoteGuiAuth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents furnace-type GUIs from force-closing due to distance when the
 * player opened them remotely through RS Integration.
 *
 * <p>{@link AbstractFurnaceMenu#stillValid(Player)} checks
 * {@code player.distanceToSqr(pos) <= 64.0} inside a lambda passed to
 * {@code ContainerLevelAccess.evaluate()}.  Our {@code ContainerDistanceMixin}
 * only targets the static helper — furnaces override {@code stillValid(Player)}
 * directly and never call it, so a separate mixin is needed.</p>
 */
@Mixin(AbstractFurnaceMenu.class)
public abstract class FurnaceMenuMixin {

    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void rsi$bypassDistanceForRemoteGui(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (RemoteGuiAuth.hasActiveAuthorization(player.getUUID())) {
            cir.setReturnValue(true);
        }
    }
}
