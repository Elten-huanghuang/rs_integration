package com.huanghuang.rsintegration.mixin.taxfreelevels;

import com.huanghuang.rsintegration.feature.experience.FlattenedExperienceCosts;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class TaxFreeEnchantmentMixin {
    @WrapOperation(method = "onEnchantmentPerformed", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;giveExperienceLevels(I)V"))
    private void rsi$chargeFixedEnchantmentExperience(Player player, int change,
                                                     Operation<Void> original) {
        int requiredLevel = 0;
        int slot = -change - 1;
        if (player.containerMenu instanceof EnchantmentMenu menu
                && slot >= 0 && slot < menu.costs.length) {
            requiredLevel = menu.costs[slot];
        }
        FlattenedExperienceCosts.chargeLevels(player, change, requiredLevel,
                adjusted -> original.call(player, adjusted));
    }
}
