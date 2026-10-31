package com.huanghuang.rsintegration.mixin.taxfreelevels;

import com.huanghuang.rsintegration.feature.experience.FlattenedExperienceCosts;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版与 RS 虚拟铁砧共用 AnvilMenu，统一从原生扣费入口处理。 */
@Mixin(AnvilMenu.class)
public abstract class TaxFreeAnvilMenuMixin {
    @Shadow @Final private DataSlot cost;

    @WrapOperation(method = "onTake", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;giveExperienceLevels(I)V"))
    private void rsi$chargeFixedExperience(Player player, int change, Operation<Void> original) {
        FlattenedExperienceCosts.chargeLevels(player, change, 0,
                adjusted -> original.call(player, adjusted));
    }

    @Inject(method = "createResult", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/DataSlot;get()I", ordinal = 1))
    private void rsi$chargeOneLevelForRename(CallbackInfo ci,
                                           @Local(ordinal = 0) int operationCost,
                                           @Local(ordinal = 2) int renameCost) {
        if (renameCost > 0 && operationCost == renameCost) cost.set(1);
    }

    @ModifyExpressionValue(method = "createResult", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/player/Abilities;instabuild:Z", ordinal = 1))
    private boolean rsi$allowExpensiveRepairs(boolean original) {
        return true;
    }
}
