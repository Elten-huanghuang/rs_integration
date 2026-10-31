package com.huanghuang.rsintegration.mixin.taxfreelevels;

import com.huanghuang.rsintegration.feature.experience.FlattenedExperienceCosts;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "forge.com.cursee.disenchanting_table.core.DisenchantingTableBlockEntity",
        remap = false)
public abstract class TaxFreeDisenchantingTableMixin {
    @WrapOperation(method = "takeExperienceFromNearestPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;setExperienceLevels(I)V",
            remap = true), require = 0)
    private static void rsi$chargeFixedExperience(ServerPlayer player, int targetLevel,
                                                 Operation<Void> original) {
        int previousLevel = player.experienceLevel;
        if (targetLevel < 0) {
            original.call(player, targetLevel);
            return;
        }
        FlattenedExperienceCosts.chargeLevels(player, targetLevel - previousLevel, 0,
                adjusted -> original.call(player, previousLevel + adjusted));
    }
}
