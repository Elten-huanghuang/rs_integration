package com.huanghuang.rsintegration.mixin.minecraft;

import com.huanghuang.rsintegration.mods.vanilla.CampfireCookingOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import javax.annotation.Nullable;

@Mixin(CampfireBlockEntity.class)
public abstract class CampfireOutputMixin implements CampfireCookingOperation.Access {
    @Shadow @Final private int[] cookingProgress;
    @Shadow @Final private int[] cookingTime;
    @Unique private CampfireCookingOperation[] rsi$cookingOperations;

    @Override public int[] rsi$cookingProgress() { return cookingProgress; }
    @Override public int[] rsi$cookingTime() { return cookingTime; }

    @Nullable
    @Override public CampfireCookingOperation rsi$getCookingOperation(int slot) {
        return rsi$cookingOperations == null ? null : rsi$cookingOperations[slot];
    }

    @Override public void rsi$setCookingOperation(int slot, @Nullable CampfireCookingOperation operation) {
        if (rsi$cookingOperations == null) rsi$cookingOperations = new CampfireCookingOperation[cookingProgress.length];
        rsi$cookingOperations[slot] = operation;
    }

    @WrapOperation(method = "cookTick", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/Containers;dropItemStack(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V"),
            require = 1)
    private static void rsi$receiveCookedOutput(Level level, double x, double y, double z, ItemStack actual,
                                                Operation<Void> drop,
                                                @Local(argsOnly = true) CampfireBlockEntity campfire,
                                                @Local(index = 5) int slot, @Local(index = 6) ItemStack input) {
        if (!CampfireCookingOperation.receive(campfire, slot, input, actual)) drop.call(level, x, y, z, actual);
    }
}
