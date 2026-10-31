package com.huanghuang.rsintegration.mixin.taxfreelevels;

import com.huanghuang.rsintegration.feature.experience.FlattenedExperienceCosts;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** 只接管已知模组的等级扣费，不全局修改 giveExperienceLevels 或经验点接口。 */
@Pseudo
@Mixin(targets = {
        "se.mickelus.tetra.blocks.workbench.WorkbenchTile",
        "fuzs.enchantinginfuser.world.inventory.InfuserMenu",
        "net.blay09.mods.waystones.core.PlayerWaystoneManager",
        "net.blay09.mods.waystones.xp.ExperienceLevelCost",
        "com.chirptheboy.disenchanting.block.disenchant.ContainerDisenchant",
        "twilightforest.inventory.slot.UncraftingSlot",
        "twilightforest.inventory.slot.UncraftingResultSlot"
}, remap = false)
public abstract class TaxFreeModLevelCostsMixin {
    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;giveExperienceLevels(I)V",
            remap = true), require = 0)
    private static void rsi$chargeFixedExperience(Player player, int change,
                                                 Operation<Void> original) {
        FlattenedExperienceCosts.chargeLevels(player, change, 0,
                adjusted -> original.call(player, adjusted));
    }
}
