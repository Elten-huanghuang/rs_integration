package com.huanghuang.rsintegration.mixin.ftbquests;

import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.util.FTBQuestsInventoryListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Prevents background inventory scans from granting unpaid consuming progress. */
@Mixin(value = FTBQuestsInventoryListener.class, remap = false)
public abstract class InventoryTaskAutoSubmissionMixin {
    @Redirect(
            method = "lambda$detect$0",
            at = @At(value = "INVOKE", target =
                    "Ldev/ftb/mods/ftbquests/quest/task/Task;submitTask("
                            + "Ldev/ftb/mods/ftbquests/quest/TeamData;"
                            + "Lnet/minecraft/server/level/ServerPlayer;"
                            + "Lnet/minecraft/world/item/ItemStack;)V"),
            remap = false, require = 0)
    private static void rsi$skipUnpaidConsumingProgress(Task task, TeamData data,
                                                        ServerPlayer player, ItemStack stack) {
        if (task instanceof ItemTask itemTask
                && itemTask.consumesResources() && !itemTask.isOnlyFromCrafting()) {
            return;
        }
        task.submitTask(data, player, stack);
    }
}
