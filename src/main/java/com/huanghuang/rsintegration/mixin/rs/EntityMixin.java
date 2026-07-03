package com.huanghuang.rsintegration.mixin.rs;

import com.huanghuang.rsintegration.network.RemoteGuiAuth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "distanceToSqr(DDD)D", at = @At("HEAD"), cancellable = true)
    private void rsi$bypassDistanceCheck3D(double x, double y, double z, CallbackInfoReturnable<Double> cir) {
        if ((Object) this instanceof Player player) {
            if (RemoteGuiAuth.hasActiveAuthorization(player.getUUID())) {
                cir.setReturnValue(0.0D);
            }
        }
    }

    @Inject(method = "distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D", at = @At("HEAD"), cancellable = true)
    private void rsi$bypassDistanceCheckVec3(Vec3 vec, CallbackInfoReturnable<Double> cir) {
        if ((Object) this instanceof Player player) {
            if (RemoteGuiAuth.hasActiveAuthorization(player.getUUID())) {
                cir.setReturnValue(0.0D);
            }
        }
    }

    @Inject(method = "distanceToSqr(Lnet/minecraft/world/entity/Entity;)D", at = @At("HEAD"), cancellable = true)
    private void rsi$bypassDistanceCheckEntity(Entity entity, CallbackInfoReturnable<Double> cir) {
        if ((Object) this instanceof Player player) {
            if (RemoteGuiAuth.hasActiveAuthorization(player.getUUID())) {
                cir.setReturnValue(0.0D);
            }
        }
    }

    @Inject(method = "distanceTo(Lnet/minecraft/world/entity/Entity;)F", at = @At("HEAD"), cancellable = true)
    private void rsi$bypassDistanceToEntity(Entity entity, CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof Player player) {
            if (RemoteGuiAuth.hasActiveAuthorization(player.getUUID())) {
                cir.setReturnValue(0.0F);
            }
        }
    }
}
