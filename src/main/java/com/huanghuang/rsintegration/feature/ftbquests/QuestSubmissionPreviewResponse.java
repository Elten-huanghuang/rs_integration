package com.huanghuang.rsintegration.feature.ftbquests;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 预览失败仍保留任务目标，让客户端打开带有失败原因的规划页。 */
final class QuestSubmissionPreviewResponse {
    private QuestSubmissionPreviewResponse() {}

    static PlanResponse failure(long questId, int repeatCount,
                                QuestSubmissionSnapshot snapshot, Component message) {
        String title = snapshot == null ? "FTB Quests" : snapshot.title();
        ItemStack target = snapshot == null ? ItemStack.EMPTY : snapshot.icon();
        return new PlanResponse(false, title, target, List.of(), Map.of(), List.of(),
                QuestSubmissionTargetIds.of(questId).toString(), "ftb_quest_submission", null,
                0, 0, 0, List.of(message), Math.max(1, Math.min(repeatCount, 1024)),
                null, null, null, 0L, false, false, false, null, Set.of(), Map.of(),
                null, null, true, List.of());
    }
}
