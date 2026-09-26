package com.huanghuang.rsintegration.mixin.ftbquests;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionAutoCompletionContext;
import com.huanghuang.rsintegration.compat.ftbquests.StorageQuestScanService;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.TeamData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Date;
import java.util.Set;

/** Keeps automatic reward/reset callbacks behind the item settlement barrier. */
@Mixin(value = TeamData.class, remap = false)
public abstract class TeamDataAutoCompletionMixin {
    @Inject(method = "checkAutoCompletion", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$deferAutoCompletion(Quest quest, CallbackInfo ci) {
        if (QuestSubmissionAutoCompletionContext.isSuppressed()) ci.cancel();
    }

    @Inject(method = "setCompleted", at = @At("HEAD"), remap = false, require = 0)
    private void rsi$captureAvailableTasks(long objectId, Date time,
                                           CallbackInfoReturnable<Boolean> cir,
                                           @Share("rsi$availableBefore")
                                           LocalRef<Set<Long>> availableBefore) {
        availableBefore.set(StorageQuestScanService.snapshotAvailableTaskIds(
                (TeamData) (Object) this));
    }

    @Inject(method = "setCompleted", at = @At("RETURN"), remap = false, require = 0)
    private void rsi$scheduleNewlyAvailableScan(long objectId, Date time,
                                                CallbackInfoReturnable<Boolean> cir,
                                                @Share("rsi$availableBefore")
                                                LocalRef<Set<Long>> availableBefore) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            Set<Long> before = availableBefore.get();
            StorageQuestScanService.scheduleNewlyAvailableScan(
                    (TeamData) (Object) this, before == null ? Set.of() : before);
        }
    }
}
