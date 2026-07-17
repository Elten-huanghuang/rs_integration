package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;

import java.util.Map;

/** Applies crafted outputs to FTB Quests crafting-only item tasks. */
final class FtbQuestCraftedItemDetector {

    private FtbQuestCraftedItemDetector() {}

    static void detect(ServerPlayer player, Map<MaterialKey, Long> crafted) {
        if (player instanceof FakePlayer || crafted.isEmpty()) return;
        ServerQuestFile questFile = ServerQuestFile.INSTANCE;
        if (questFile == null || questFile.isLoading()) return;
        TeamData teamData = TeamData.get(player);
        if (teamData == null || teamData.isLocked()) return;

        questFile.withPlayerContext(player, () -> {
            for (Task task : questFile.getCraftingTasks()) {
                if (!(task instanceof ItemTask itemTask)
                        || !itemTask.isOnlyFromCrafting()
                        || itemTask.isTaskScreenOnly()
                        || teamData.isCompleted(itemTask)
                        || !teamData.canStartTasks(itemTask.getQuest())) {
                    continue;
                }
                for (Map.Entry<MaterialKey, Long> entry : crafted.entrySet()) {
                    ItemStack prototype = entry.getKey().toStack(1);
                    boolean matches;
                    try {
                        matches = itemTask.test(prototype);
                    } catch (RuntimeException exception) {
                        continue;
                    }
                    if (!matches) continue;
                    long remaining = Math.max(0L,
                            itemTask.getMaxProgress() - teamData.getProgress(itemTask));
                    long amount = Math.min(remaining, entry.getValue());
                    if (amount > 0L) teamData.addProgress(itemTask, amount);
                }
            }
        });
    }
}
