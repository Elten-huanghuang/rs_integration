package com.huanghuang.rsintegration.mixin.minecraft;

import com.huanghuang.rsintegration.network.gui.RemoteGuiAuth;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

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
        if (player instanceof net.minecraft.server.level.ServerPlayer sp
                && RemoteGuiAuth.isAuthorized(sp, menu)) {
            return true;
        }
        return original.call(menu, player);
    }
}
