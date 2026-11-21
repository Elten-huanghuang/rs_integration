package com.huanghuang.rsintegration.mixin.botania;

import com.huanghuang.rsintegration.crafting.batch.NativeItemOutput;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import vazkii.botania.common.block.block_entity.BreweryBlockEntity;

import javax.annotation.Nullable;

@Pseudo
@Mixin(targets = "vazkii.botania.common.block.block_entity.BreweryBlockEntity", remap = false)
public abstract class BreweryOutputMixin implements NativeItemOutput {
    @Unique @Nullable private Receiver rsi$outputReceiver;

    @Override public Receiver rsi$getOutputReceiver() { return rsi$outputReceiver; }
    @Override public void rsi$setOutputReceiver(@Nullable Receiver receiver) { rsi$outputReceiver = receiver; }

    @WrapOperation(method = "commonTick", at = @At(value = "INVOKE", remap = true, target =
            "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"), require = 1)
    private static boolean rsi$receiveOutput(Level level, Entity entity, Operation<Boolean> spawn,
                                             @Local(argsOnly = true) BreweryBlockEntity brewery) {
        return entity instanceof ItemEntity item && brewery instanceof NativeItemOutput receiver
                && receiver.rsi$receiveNativeOutput(brewery.recipe, item.getItem(), true)
                || spawn.call(level, entity);
    }
}
