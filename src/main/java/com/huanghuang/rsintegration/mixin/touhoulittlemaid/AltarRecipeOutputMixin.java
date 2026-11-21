package com.huanghuang.rsintegration.mixin.touhoulittlemaid;

import com.huanghuang.rsintegration.mods.touhoulittlemaid.TlmAltarOutputHook;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import javax.annotation.Nullable;
import java.util.function.Predicate;

@Pseudo
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.crafting.AltarRecipe", remap = false)
public abstract class AltarRecipeOutputMixin implements TlmAltarOutputHook {
    @Unique @Nullable private Predicate<Entity> rsi$altarOutputReceiver;

    @Override
    public void rsi$setAltarOutputReceiver(@Nullable Predicate<Entity> receiver) {
        rsi$altarOutputReceiver = receiver;
    }

    // 此时原生配方已把输入 NBT 写入实体，尚未把实体加入世界。
    @WrapOperation(method = "spawnOutputEntity", at = @At(value = "INVOKE", remap = true, target =
            "Lnet/minecraft/server/level/ServerLevel;tryAddFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)Z"),
            require = 1)
    private boolean rsi$receiveAltarOutput(ServerLevel level, Entity entity, Operation<Boolean> spawn) {
        return rsi$altarOutputReceiver != null && rsi$altarOutputReceiver.test(entity)
                || spawn.call(level, entity);
    }
}
