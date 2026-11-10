package com.huanghuang.rsintegration.mixin.ftbquests;

import com.huanghuang.rsintegration.feature.ftbquests.client.FtbQuestJeiRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Refreshes quest recipes after repeatable-quest cooldown state changes. */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.net.ClearRepeatCooldownMessage", remap = false)
public abstract class ClearRepeatCooldownMessageMixin {
    @Inject(method = "lambda$handle$0", at = @At("RETURN"))
    private void rsi$requestQuestRecipeRefresh(CallbackInfo ci) {
        FtbQuestJeiRuntime.requestRefresh();
    }
}
