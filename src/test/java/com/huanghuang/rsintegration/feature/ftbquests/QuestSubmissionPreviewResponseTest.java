package com.huanghuang.rsintegration.feature.ftbquests;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestSubmissionPreviewResponseTest extends BootstrapTest {
    @Test
    void rejectedPreviewRetainsItsTargetAndBlocksExecutionAfterPacketRoundTrip() {
        long questId = 0x1234L;
        QuestSubmissionSnapshot snapshot = new QuestSubmissionSnapshot(questId, "测试任务",
                new ItemStack(Items.IRON_INGOT), false, false,
                QuestSubmissionEligibility.QUEST_NOT_STARTABLE, List.of(), List.of(), 0, false);
        Component message = Component.translatable("rsi.ftb_quest.error.not_eligible");
        PlanResponse failure = QuestSubmissionPreviewResponse.failure(questId, 2, snapshot, message);
        PlanResponse decoded = PlanResponsePacket.decodePayload(
                PlanResponsePacket.encodePayload(failure, 0L)).plan();

        assertFalse(decoded.success());
        assertTrue(decoded.executionBlocked());
        assertEquals(questId, QuestSubmissionTargetIds.questId(
                ResourceLocation.tryParse(decoded.recipeId())));
        assertEquals("测试任务", decoded.targetName());
        assertTrue(ItemStack.isSameItemSameTags(snapshot.icon(), decoded.targetResult()));
        assertEquals(message, decoded.modWarnings().get(0));
        assertEquals(2, decoded.repeatCount());
    }

    @Test
    void unavailableQuestStillReturnsADisplayableFailureWithTheRequestedQuestId() {
        PlanResponse failure = QuestSubmissionPreviewResponse.failure(7L, 1, null,
                Component.translatable("rsi.plan.failure.internal_error"));
        assertTrue(QuestSubmissionTargetIds.isQuestSubmission(
                ResourceLocation.tryParse(failure.recipeId())));
        assertTrue(failure.executionBlocked());
        assertFalse(failure.targetName().isBlank());
        assertFalse(failure.modWarnings().isEmpty());
    }
}
