package com.huanghuang.rsintegration.mixin.majruszsdifficulty;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents magnet-only Majrusz treasure bag drops from entering player inventory. */
@Mixin(ItemEntity.class)
public abstract class MajruszTreasureBagItemEntityMixin {

    private static final String MAGNET_ONLY_TAG = "RSIMajruszTreasureBagMagnetOnly";

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void rsi$preventTreasureBagPlayerPickup(Player player, CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (self.getPersistentData().getBoolean(MAGNET_ONLY_TAG)) {
            ci.cancel();
        }
    }
}
