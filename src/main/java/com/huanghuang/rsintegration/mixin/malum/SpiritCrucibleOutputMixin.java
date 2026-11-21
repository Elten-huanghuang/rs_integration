package com.huanghuang.rsintegration.mixin.malum;

import com.huanghuang.rsintegration.crafting.batch.NativeItemOutput;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sammy.malum.common.recipe.SpiritFocusingRecipe;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import javax.annotation.Nullable;

@Pseudo
@Mixin(targets = "com.sammy.malum.common.block.curiosities.spirit_crucible.SpiritCrucibleCoreBlockEntity", remap = false)
public abstract class SpiritCrucibleOutputMixin implements NativeItemOutput {
    @Shadow private SpiritFocusingRecipe recipe;
    @Unique @Nullable private Receiver rsi$outputReceiver;

    @Override public Receiver rsi$getOutputReceiver() { return rsi$outputReceiver; }
    @Override public void rsi$setOutputReceiver(@Nullable Receiver receiver) { rsi$outputReceiver = receiver; }

    @WrapOperation(method = "craft", at = @At(value = "INVOKE", remap = true, ordinal = 0, target =
            "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"), require = 1)
    private boolean rsi$receivePrimary(Level level, Entity entity, Operation<Boolean> spawn) {
        return entity instanceof ItemEntity item && rsi$receiveNativeOutput(recipe, item.getItem(), true)
                || spawn.call(level, entity);
    }

    @WrapOperation(method = "craft", at = @At(value = "INVOKE", remap = true, ordinal = 1, target =
            "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"), require = 1)
    private boolean rsi$receiveBonus(Level level, Entity entity, Operation<Boolean> spawn) {
        return entity instanceof ItemEntity item && rsi$receiveNativeOutput(recipe, item.getItem(), false)
                || spawn.call(level, entity);
    }
}
