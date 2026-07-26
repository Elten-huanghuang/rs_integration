package com.huanghuang.rsintegration.mixin.malum;

import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskAbilities;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskAbilityService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = com.sammy.malum.core.handlers.TouchOfDarknessHandler.class, remap = false)
public class TouchOfDarknessHandlerMixin {

    @Inject(method = "reject", at = @At("RETURN"))
    private void rsi$unlockActiveResonanceDisk(LivingEntity entity, CallbackInfo ci) {
        if (!(entity instanceof ServerPlayer player)) return;
        ResonanceDiskAbilityService.UnlockResult result =
                ResonanceDiskAbilityService.unlockActiveDisk(
                        player, ResonanceDiskAbilities.MALUM_VOID_FAVOR);
        if (result == ResonanceDiskAbilityService.UnlockResult.UNLOCKED) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.resonance.void_favor_unlocked"));
        } else if (result == ResonanceDiskAbilityService.UnlockResult.NO_RESONANCE_DISK) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.resonance.void_favor_no_disk"));
        }
    }
}
