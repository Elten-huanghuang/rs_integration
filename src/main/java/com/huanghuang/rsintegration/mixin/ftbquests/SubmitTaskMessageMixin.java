package com.huanghuang.rsintegration.mixin.ftbquests;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.compat.ftbquests.NativeItemTaskSubmissionService;
import dev.ftb.mods.ftbquests.net.SubmitTaskMessage;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/** Adds the RS fallback only to an explicit click on FTB Quests' submit button. */
@Mixin(value = SubmitTaskMessage.class, remap = false)
public abstract class SubmitTaskMessageMixin {
    @Shadow(remap = false) @Final private long taskId;

    @Inject(
            method = "handle",
            at = @At(value = "INVOKE", target =
                    "Ldev/ftb/mods/ftbquests/quest/TeamData;isLocked()Z"),
            cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD,
            remap = false, require = 1)
    private void rsi$submitMissingFromStorage(@Coerce Object context, CallbackInfo ci,
                                               ServerPlayer player, TeamData data) {
        if (data == null || data.isLocked()) return;

        Task task = data.getFile().getTask(taskId);
        if (!(task instanceof ItemTask itemTask)
                || !(data.getFile() instanceof ServerQuestFile file)
                || !data.canStartTasks(task.getQuest())) return;

        boolean[] handled = {false};
        try {
            file.withPlayerContext(player, () -> handled[0] =
                    NativeItemTaskSubmissionService.handleExplicitSubmission(itemTask, data, player));
        } catch (RuntimeException | LinkageError exception) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-FTBQuests] Submission hook failed for task {}; continuing native packet",
                    task.getId(), exception);
        }

        // Leave the packet untouched unless RSI actually reserved or settled
        // something. FTB then runs its own version-specific submission path.
        if (handled[0]) ci.cancel();
    }
}
