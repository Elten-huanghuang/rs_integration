package com.huanghuang.rsintegration.mixin.malum;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskAbilities;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskAbilityService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = com.sammy.malum.common.block.curiosities.weeping_well.VoidConduitBlockEntity.class,
        remap = false)
public class VoidConduitBlockEntityMixin {

    @Inject(method = "spitOutItem", at = @At("HEAD"))
    private void rsi$unlockResonanceDisk(ItemStack stack,
                                         CallbackInfoReturnable<net.minecraft.world.item.Item> cir) {
        var level = ((BlockEntity) (Object) this).getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;
        ResonanceDiskAbilityService.UnlockResult result =
                ResonanceDiskAbilityService.unlockDiskStack(
                        serverLevel, stack, ResonanceDiskAbilities.MALUM_VOID_FAVOR);
        if (result == ResonanceDiskAbilityService.UnlockResult.UNLOCKED) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-Resonance] Resonance disk unlocked Malum void-favor crafting at {}",
                    ((BlockEntity) (Object) this).getBlockPos());
        }
    }
}
