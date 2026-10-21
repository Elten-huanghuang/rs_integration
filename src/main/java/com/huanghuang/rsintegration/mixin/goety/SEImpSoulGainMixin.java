package com.huanghuang.rsintegration.mixin.goety;

import com.huanghuang.rsintegration.mods.goety.magic.GoetySoulOverflow;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Arca 达到上限时，把本次获取的剩余灵魂转入已绑定网络。 */
@Pseudo
@Mixin(targets = "com.Polarice3.Goety.common.capabilities.soulenergy.SEImp", remap = false)
public abstract class SEImpSoulGainMixin {
    @Shadow private int soulEnergy;

    @Inject(method = "increaseSE", at = @At("HEAD"), cancellable = true, require = 0)
    private void rsi$routeArcaOverflow(int increase, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer player = GoetySoulOverflow.currentGainPlayer();
        if (player == null || increase <= 0) return;

        long room = Math.max(0L, (long) GoetySoulOverflow.maxArcaSouls() - soulEnergy);
        int accepted = (int) Math.min((long) increase, room);
        soulEnergy = (int) Math.min(Integer.MAX_VALUE, (long) soulEnergy + accepted);
        int overflow = increase - accepted;
        if (overflow > 0) GoetySoulOverflow.route(player, overflow);
        cir.setReturnValue(accepted > 0);
    }
}
