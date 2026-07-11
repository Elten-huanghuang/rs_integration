package com.huanghuang.rsintegration.mixin.minecraft;

import com.huanghuang.rsintegration.network.gui.RemoteGuiAuth;
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
        if (RemoteGuiAuth.hasActiveAuthorizationForBlock(player.getUUID(), block)) {
            cir.setReturnValue(true);
            cir.cancel();
        }
    }
}
