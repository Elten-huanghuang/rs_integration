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

/** Direct adapter to FTB Quests, loaded only when FTB Quests and Teams are present. */
final class FtbQuestExternalItemDetector {

    private FtbQuestExternalItemDetector() {}

    static void detect(ServerPlayer player, Map<MaterialKey, Long> inserted) {
        if (player instanceof FakePlayer || inserted.isEmpty()) return;
        ServerQuestFile questFile = ServerQuestFile.INSTANCE;
        if (questFile == null || questFile.isLoading()) return;
        TeamData teamData = TeamData.get(player);
        if (teamData == null || teamData.isLocked()) return;

        questFile.withPlayerContext(player, () -> {
            for (Task task : questFile.getSubmitTasks()) {
                if (!(task instanceof ItemTask itemTask)
                        || itemTask.consumesResources()
                        || itemTask.isOnlyFromCrafting()
                        || itemTask.isTaskScreenOnly()
                        || teamData.isCompleted(itemTask)
                        || !teamData.canStartTasks(itemTask.getQuest())) {
                    continue;
                }
                for (Map.Entry<MaterialKey, Long> entry : inserted.entrySet()) {
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
