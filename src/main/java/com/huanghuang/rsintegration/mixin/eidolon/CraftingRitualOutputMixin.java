package com.huanghuang.rsintegration.mixin.eidolon;

import com.huanghuang.rsintegration.crafting.batch.NativeItemOutput;
import com.huanghuang.rsintegration.mods.eidolon.EidolonRitualOutput;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import elucent.eidolon.api.ritual.Ritual;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "elucent.eidolon.common.ritual.CraftingRitual", remap = false)
public abstract class CraftingRitualOutputMixin implements EidolonRitualOutput {
    @WrapOperation(method = "start", at = @At(value = "INVOKE", remap = true, target =
            "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"), require = 1)
    private boolean rsi$receiveOutput(Level level, Entity entity, Operation<Boolean> spawn,
                                      @Local(argsOnly = true) BlockPos pos) {
        return entity instanceof ItemEntity item && level.getBlockEntity(pos) instanceof NativeItemOutput receiver
                && receiver.rsi$receiveNativeOutput(((Ritual) (Object) this).getRegistryName(), item.getItem(), true)
                || spawn.call(level, entity);
    }
}
