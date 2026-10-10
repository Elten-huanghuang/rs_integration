package com.huanghuang.rsintegration.mixin.untileternity;

import com.carrot123.until_eternity.item.ImFullInventoryHelper;
import com.carrot123.until_eternity.item.ModItems;
import com.huanghuang.rsintegration.resonance.bridge.ResonanceInventoryBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 让原模组的饱食度与口渴保护复用同一背包判定。 */
@Mixin(value = ImFullInventoryHelper.class, remap = false)
public abstract class ImFullInventoryHelperMixin {
    @Inject(method = "hasImFullItem", at = @At("RETURN"), cancellable = true, remap = false)
    private static void rsi$includeResonanceDisk(Player player,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || !(player instanceof ServerPlayer serverPlayer)) return;
        if (ResonanceInventoryBridge.hasItem(serverPlayer,
                stack -> stack.is(ModItems.IMFULL.get()))) {
            cir.setReturnValue(true);
        }
    }
}
