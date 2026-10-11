package com.huanghuang.rsintegration.mixin.tacz;

import com.huanghuang.rsintegration.mods.tacz.TaczResonanceAmmoHandler;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.resonance.passive.PassiveEffectEngine;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tacz.guns.item.ModernKineticGunScriptAPI;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 默认枪械与 Lua 脚本通过同一个入口读取并实际扣除盘内散装弹药。 */
@Mixin(value = ModernKineticGunScriptAPI.class, remap = false)
public abstract class ModernKineticGunScriptAmmoMixin {
    @Shadow public abstract LivingEntity getShooter();

    @WrapOperation(method = {"consumeAmmoFromPlayer", "hasAmmoToConsume"},
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/world/entity/LivingEntity;getCapability(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/core/Direction;)Lnet/minecraftforge/common/util/LazyOptional;"),
            remap = false, require = 1)
    private LazyOptional<IItemHandler> rsi$includeResonanceAmmo(
            LivingEntity shooter, Capability<IItemHandler> capability, Direction side,
            Operation<LazyOptional<IItemHandler>> original) {
        return TaczResonanceAmmoHandler.includeResonanceAmmo(shooter, capability,
                original.call(shooter, capability, side));
    }

    @Inject(method = "consumeAmmoFromPlayer", at = @At("RETURN"), remap = false, require = 1)
    private void rsi$syncConsumedAmmo(int amount, CallbackInfoReturnable<Integer> callback) {
        // 装填结束后立即同步，避免 HUD 继续显示扣弹前的盘内数量。
        if (callback.getReturnValueI() > 0 && getShooter() instanceof ServerPlayer player
                && RSIntegrationConfig.ENABLE_TACZ.get()
                && RSIntegrationConfig.ENABLE_RS_PASSIVE_EFFECTS.get()) {
            PassiveEffectEngine.refreshPlayer(player);
        }
    }
}
