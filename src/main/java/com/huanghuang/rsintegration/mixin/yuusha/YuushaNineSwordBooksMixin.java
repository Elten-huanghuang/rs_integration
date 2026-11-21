package com.huanghuang.rsintegration.mixin.yuusha;

import com.huanghuang.rsintegration.mods.slashblade.NineSwordBladeSelection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.heike233.chapterofyuusha3.comm.compat.curios.item.moonstone.NineSwordBooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NineSwordBooks.class, remap = false)
public abstract class YuushaNineSwordBooksMixin {

    @Inject(method = "countValidSwords", at = @At("HEAD"), cancellable = true)
    private void rsi$countSelectedBlades(Player player, CallbackInfoReturnable<Integer> cir) {
        if (player instanceof ServerPlayer serverPlayer) {
            cir.setReturnValue(NineSwordBladeSelection.count(serverPlayer));
        }
    }

    @Inject(method = "calculateTotalSwordDamage", at = @At("HEAD"), cancellable = true)
    private static void rsi$sumSelectedBladeDamage(Player player, CallbackInfoReturnable<Float> cir) {
        if (player instanceof ServerPlayer serverPlayer) {
            cir.setReturnValue(NineSwordBladeSelection.totalDamage(serverPlayer));
        }
    }
}
