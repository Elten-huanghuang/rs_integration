package com.huanghuang.rsintegration.mixin.malum;

import com.huanghuang.rsintegration.mods.malum.MalumAltarPedestals;
import com.sammy.malum.common.block.curiosities.spirit_altar.AltarCraftingHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = AltarCraftingHelper.class, remap = false)
public abstract class AltarCraftingHelperMixin {
    @Inject(method = "capturePedestals(Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/core/BlockPos;)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void rsi$captureReserved(Level level, BlockPos pos,
                                           CallbackInfoReturnable<List<?>> callback) {
        List<?> reserved = MalumAltarPedestals.captureReserved(level, pos);
        if (reserved != null) callback.setReturnValue(reserved);
    }

    @Inject(method = "capturePedestals(Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/core/BlockPos;III)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true, require = 1)
    private static void rsi$excludeOtherAltarInputs(Level level, BlockPos pos,
                                                  int horizontal, int vertical, int depth,
                                                  CallbackInfoReturnable<List<?>> callback) {
        callback.setReturnValue(MalumAltarPedestals.filterCaptured(level, pos, callback.getReturnValue()));
    }
}
