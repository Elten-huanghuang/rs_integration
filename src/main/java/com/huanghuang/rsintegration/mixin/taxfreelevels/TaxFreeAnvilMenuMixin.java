package com.huanghuang.rsintegration.mixin.taxfreelevels;

import com.huanghuang.rsintegration.feature.experience.FlattenedExperienceCosts;
import com.huanghuang.rsintegration.feature.experience.ExperienceWandAccess;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 原版与 RS 虚拟铁砧共用 AnvilMenu，统一从原生扣费入口处理。 */
@Mixin(AnvilMenu.class)
public abstract class TaxFreeAnvilMenuMixin extends ItemCombinerMenu {
    @Shadow @Final private DataSlot cost;
    @Unique private boolean rsi$taxFree;

    protected TaxFreeAnvilMenuMixin(MenuType<?> type, int id, Inventory inventory,
                                    ContainerLevelAccess access) {
        super(type, id, inventory, access);
    }

    @Inject(method = "createResult", at = @At("HEAD"))
    private void rsi$refreshWandPresence(CallbackInfo ci) {
        rsi$taxFree = ExperienceWandAccess.hasWand(player);
    }

    @Inject(method = "mayPickup", at = @At("HEAD"))
    private void rsi$recheckWandBeforePickup(Player player, boolean hasStack,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (rsi$taxFree != ExperienceWandAccess.hasWand(player)) {
            ((AnvilMenu) (Object) this).createResult();
        }
    }

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
        if (rsi$taxFree && renameCost > 0 && operationCost == renameCost) cost.set(1);
    }

    @ModifyExpressionValue(method = "createResult", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/player/Abilities;instabuild:Z", ordinal = 1))
    private boolean rsi$allowExpensiveRepairs(boolean original) {
        return original || rsi$taxFree;
    }
}
