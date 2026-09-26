package com.huanghuang.rsintegration.mixin.ftbquests;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.compat.ftbquests.client.FtbQuestJeiRuntime;
import dev.ftb.mods.ftbquests.net.ClearRepeatCooldownMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Refreshes quest recipes after repeatable-quest cooldown state changes. */
@Mixin(value = ClearRepeatCooldownMessage.class, remap = false)
public abstract class ClearRepeatCooldownMessageMixin {
    @Inject(method = "lambda$handle$0", at = @At("RETURN"))
    private void rsi$requestQuestRecipeRefresh(CallbackInfo ci) {
        FtbQuestJeiRuntime.requestRefresh();
    }
}
