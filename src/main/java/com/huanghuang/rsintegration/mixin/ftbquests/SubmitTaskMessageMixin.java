package com.huanghuang.rsintegration.mixin.ftbquests;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.compat.ftbquests.NativeItemTaskSubmissionService;
import dev.ftb.mods.ftbquests.net.SubmitTaskMessage;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the RS fallback only to an explicit click on FTB Quests' submit button. */
@Mixin(value = SubmitTaskMessage.class, remap = false)
public abstract class SubmitTaskMessageMixin {
    @Shadow private long taskId;

    @Inject(
            method = "handle",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void rsi$submitMissingFromRs(@Coerce Object context, CallbackInfo ci) {
        RSIntegrationMod.LOGGER.info(
                "[RSI-FTBQuests] Submit packet received task={} context={}",
                taskId, context == null ? "null" : context.getClass().getName());
        ServerPlayer player;
        try {
            Object candidate = context.getClass().getMethod("getPlayer").invoke(context);
            if (!(candidate instanceof ServerPlayer serverPlayer)) return;
            player = serverPlayer;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return;
        }
        TeamData data = TeamData.get(player);
        if (data == null || data.isLocked()) return;
        BaseQuestFile file = data.getFile();
        if (!(file instanceof ServerQuestFile)) return;
        Task task = file.getTask(taskId);
        if (!(task instanceof ItemTask itemTask) || !data.canStartTasks(task.getQuest())) return;
        if (NativeItemTaskSubmissionService.handleExplicitSubmission(itemTask, data, player)) {
            ci.cancel();
        }
    }
}
