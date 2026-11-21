package com.huanghuang.rsintegration.mixin.wizardsreborn;

import com.huanghuang.rsintegration.mods.wizardsreborn.WRCrystalOutputHook;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import mod.maxbogomol.wizards_reborn.common.block.crystal.CrystalBlockEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "mod.maxbogomol.wizards_reborn.common.crystalritual.CrystalInfusionCrystalRitual", remap = false)
public abstract class CrystalInfusionOutputMixin implements WRCrystalOutputHook.NativeInfusion {
    @WrapOperation(method = "end", at = @At(value = "INVOKE", remap = true, target =
            "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"),
            require = 1)
    private boolean rsi$receiveInfusionOutput(Level level, Entity entity, Operation<Boolean> spawn,
                                              @Local(argsOnly = true) CrystalBlockEntity crystal) {
        return entity instanceof ItemEntity item
                && crystal instanceof WRCrystalOutputHook hook && hook.rsi$receiveCrystalOutput(item.getItem())
                || spawn.call(level, entity);
    }
}
