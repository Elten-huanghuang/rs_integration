package com.huanghuang.rsintegration.mixin.tacz;

import com.huanghuang.rsintegration.mods.tacz.TaczResonanceAmmoHandler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 在原生换弹检查的背包扫描阶段追加共振盘，保留满弹匣与虚拟弹药检查。 */
@Mixin(value = AbstractGunItem.class, remap = false)
public abstract class AbstractGunItemAmmoMixin {
    @WrapOperation(method = "canReload", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/LivingEntity;getCapability(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/core/Direction;)Lnet/minecraftforge/common/util/LazyOptional;"),
            remap = false, require = 1)
    private LazyOptional<IItemHandler> rsi$includeResonanceAmmo(
            LivingEntity shooter, Capability<IItemHandler> capability, Direction side,
            Operation<LazyOptional<IItemHandler>> original) {
        return TaczResonanceAmmoHandler.includeResonanceAmmo(shooter, capability,
                original.call(shooter, capability, side));
    }
}
