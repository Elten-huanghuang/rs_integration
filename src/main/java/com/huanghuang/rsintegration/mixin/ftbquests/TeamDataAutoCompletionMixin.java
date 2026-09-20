package com.huanghuang.rsintegration.mixin.ftbquests;

import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionAutoCompletionContext;
import com.huanghuang.rsintegration.compat.ftbquests.StorageQuestScanService;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.TeamData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Date;

/** Keeps automatic reward/reset callbacks behind the item settlement barrier. */
@Mixin(value = TeamData.class, remap = false)
public abstract class TeamDataAutoCompletionMixin {
    @Inject(method = "checkAutoCompletion", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$deferAutoCompletion(Quest quest, CallbackInfo ci) {
        if (QuestSubmissionAutoCompletionContext.isSuppressed()) ci.cancel();
    }

    @Inject(method = "setCompleted", at = @At("RETURN"), remap = false, require = 0)
    private void rsi$scheduleRetrospectiveScan(long objectId, Date time,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            StorageQuestScanService.scheduleRetrospectiveScan((TeamData) (Object) this);
        }
    }
}
