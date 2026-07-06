package com.huanghuang.rsintegration.mixin.minecraft;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.gui.RemoteGuiAuth;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTickMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    public void rsi$tickHead(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (RemoteGuiAuth.hasActiveAuthorization(self.getUUID())) {
            RSIntegrationMod.LOGGER.debug("[RSI-Tick] Auth active for player={}, container={}",
                    self.getName().getString(),
                    self.containerMenu != null ? self.containerMenu.getClass().getSimpleName() : "null");
        }
    }

    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;stillValid(Lnet/minecraft/world/entity/player/Player;)Z"
            )
    )
    public boolean rsi$wrapStillValidTick(AbstractContainerMenu menu, Player player,
                                          Operation<Boolean> original) {
        if (RemoteGuiAuth.hasActiveAuthorization(player.getUUID())) {
            RSIntegrationMod.LOGGER.debug("[RSI-Tick] stillValid bypassed for {}",
                    player.getName().getString());
            return true;
        }
        boolean result = original.call(menu, player);
        if (!result) {
            RSIntegrationMod.LOGGER.debug("[RSI-Tick] stillValid=FALSE for {} menu={} (no auth)",
                    player.getName().getString(), menu.getClass().getSimpleName());
        }
        return result;
    }
}
