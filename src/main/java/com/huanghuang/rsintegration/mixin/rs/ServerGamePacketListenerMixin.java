package com.huanghuang.rsintegration.mixin.rs;

import com.huanghuang.rsintegration.network.RemoteGuiAuth;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Bypasses per-packet {@code stillValid} checks for authorized remote GUI
 * access.  Uses {@code @WrapOperation} so multiple mods chain without
 * silently overriding one another.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {

    @WrapOperation(
            method = { "handleContainerClick", "handleContainerButtonClick" },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;stillValid(Lnet/minecraft/world/entity/player/Player;)Z"
            )
    )
    public boolean rsi$wrapStillValidClicks(AbstractContainerMenu menu, Player player,
                                             Operation<Boolean> original) {
        if (RemoteGuiAuth.hasActiveAuthorization(player.getUUID())) {
            return true;
        }
        return original.call(menu, player);
    }
}
