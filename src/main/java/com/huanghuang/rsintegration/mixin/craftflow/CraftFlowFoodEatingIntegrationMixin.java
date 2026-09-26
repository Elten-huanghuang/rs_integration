package com.huanghuang.rsintegration.mixin.craftflow;
import java.lang.reflect.Method;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.UUID;

/** Rejects stale or custom-client requests for CraftFlow's automatic eating feature. */
@Pseudo
@Mixin(targets = "com.ybm.craftflow.server.FoodEatingIntegration", remap = false)
public abstract class CraftFlowFoodEatingIntegrationMixin {
    @Inject(method = "setBlacklist", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableBlacklistWrites(Player player, Set<ResourceLocation> blacklist,
                                                   CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = {"syncBlacklistToClient", "handleBlacklistRequest"},
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableBlacklistSync(ServerPlayer player, CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "eatAllFoods", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableEating(ServerPlayer player, int mode, CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "handleConfirm", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disableEatingConfirmation(ServerPlayer player, long nonce,
                                                      boolean accepted, CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "clearPending", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rsi$disablePendingState(UUID playerId, CallbackInfo callback) {
        callback.cancel();
    }
}
