package com.huanghuang.rsintegration.mixin.retraining;

import com.huanghuang.rsintegration.villager.tradelock.VillagerTradeLockHooks;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.mrbysco.retraining.CommonRetraining", remap = false)
public abstract class RetrainingTradeLockMixin {
    @Inject(method = "resetTrades", at = @At("HEAD"), cancellable = true, remap = false)
    private static void rsIntegration$lockBookmarkedTrade(ServerPlayer player, CallbackInfo callback) {
        if (VillagerTradeLockHooks.blockRefresh(player)) callback.cancel();
    }
}
