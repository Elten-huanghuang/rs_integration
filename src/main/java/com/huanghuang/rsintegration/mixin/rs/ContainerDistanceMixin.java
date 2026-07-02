package com.huanghuang.rsintegration.mixin.rs;

import com.huanghuang.rsintegration.network.RemoteGuiAuth;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerMenu.class)
public abstract class ContainerDistanceMixin {

    @Inject(method = "stillValid(Lnet/minecraft/world/inventory/ContainerLevelAccess;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/block/Block;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void rsi$bypassDistanceCheck(ContainerLevelAccess access, Player player, Block block,
                                                 CallbackInfoReturnable<Boolean> cir) {
        // Client: always bypass — the client has no Auth data and
        // the server is authoritative for security (it sends a
        // close-packet if the authorization is missing).
        // Server: check the authorization before bypassing.
        if (player.level().isClientSide
                || RemoteGuiAuth.hasActiveAuthorization(player.getUUID())) {
            cir.setReturnValue(true);
        }
    }
}
