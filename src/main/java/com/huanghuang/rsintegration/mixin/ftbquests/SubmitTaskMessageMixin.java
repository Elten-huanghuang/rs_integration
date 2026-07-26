package com.huanghuang.rsintegration.mixin.ftbquests;

import com.huanghuang.rsintegration.compat.ftbquests.NativeItemTaskSubmissionService;
import dev.ftb.mods.ftbquests.net.SubmitTaskMessage;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the RS fallback only to an explicit click on FTB Quests' submit button. */
@Mixin(value = SubmitTaskMessage.class, remap = false)
public abstract class SubmitTaskMessageMixin {
    @Inject(
            method = "lambda$handle$0",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void rsi$submitMissingFromRs(Task task, TeamData data,
                                                 ServerPlayer player, CallbackInfo ci) {
        if (task instanceof ItemTask itemTask
                && NativeItemTaskSubmissionService.handleExplicitSubmission(itemTask, data, player)) {
            ci.cancel();
        }
    }
}
