package com.huanghuang.rsintegration.mixin.taxfreelevels;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AnvilScreen.class)
public abstract class TaxFreeAnvilScreenMixin {
    @ModifyExpressionValue(method = "renderLabels", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/player/Abilities;instabuild:Z"))
    private boolean rsi$showActualRepairCost(boolean original) {
        return true;
    }
}
