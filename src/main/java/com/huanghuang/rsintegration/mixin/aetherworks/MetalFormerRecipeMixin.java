package com.huanghuang.rsintegration.mixin.aetherworks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.nbt.CompoundTag;
import net.sirplop.aetherworks.recipe.MetalFormerRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 1.1.2 的月凝宝石原料没有 NBT，原生精确比较需允许双方均为空。 */
@Mixin(value = MetalFormerRecipe.class, remap = false)
public abstract class MetalFormerRecipeMixin {
    @WrapOperation(method = "matches(Lnet/sirplop/aetherworks/recipe/MetalFormerContext;Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/CompoundTag;equals(Ljava/lang/Object;)Z"),
            require = 0, remap = false)
    private static boolean rsi$compareNullableInputTag(CompoundTag inputTag, Object actualTag,
                                                      Operation<Boolean> original) {
        return inputTag == null ? actualTag == null : original.call(inputTag, actualTag);
    }
}
