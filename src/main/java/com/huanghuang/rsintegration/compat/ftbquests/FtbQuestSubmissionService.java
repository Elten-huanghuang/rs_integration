package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import net.minecraftforge.network.PacketDistributor;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Coordinates recursive material production before the quest submission transaction. */
public final class FtbQuestSubmissionService {

    private FtbQuestSubmissionService() {}

    public static void preview(ServerPlayer player, long questId) {
        QuestSubmissionSnapshot snapshot = FtbQuestSubmissionScanner.findServer(player, questId);
        if (snapshot == null || !snapshot.eligible()) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.not_eligible"));
            return;
        }
        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.no_network"));
            return;
        }

        QuestSubmissionPlan questPlan = FtbQuestSubmissionPlanner.plan(player, snapshot, network);
        List<PlanStep> steps = questPlan.graphView().nodes().stream()
                .map(node -> node.asPlanStep())
                .toList();
        ItemStack target = snapshot.icon().isEmpty()
                ? snapshot.requirements().get(0).displayStack().copyWithCount(1)
                : snapshot.icon().copyWithCount(1);
        PlanResponse plan = new PlanResponse(questPlan.feasible(), snapshot.title(), target,
                steps, questPlan.materials(), questPlan.missing(),
                QuestSubmissionTargetIds.of(questId).toString(),
                "ftb_quest_submission", null, 0, 0, 0,
                List.of(), 1, null, null, null, 0L,
                false, false, false, null, java.util.Set.of(), java.util.Map.of(),
                null, questPlan.graphView());
        BatchCraftNetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player), new PlanResponsePacket(plan));
    }

    public static void execute(ServerPlayer player, long questId) {
        QuestSubmissionSnapshot snapshot = FtbQuestSubmissionScanner.findServer(player, questId);
        if (snapshot == null || !snapshot.eligible()) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.not_eligible"));
            return;
        }
        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.no_network"));
            return;
        }

        QuestSubmissionPlan plan = FtbQuestSubmissionPlanner.plan(player, snapshot, network);
        if (!plan.feasible()) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials",
                    CraftPacketUtils.formatMissingSummary(plan.missing())));
            return;
        }

        List<CraftingResolver.ResolutionStep> steps = projectSteps(plan);
        if (steps.isEmpty()) {
            FtbQuestSubmissionExecutor.submit(player, questId, network);
            return;
        }

        if (steps.stream().allMatch(step -> step.modType() == ModType.GENERIC)) {
            if (CraftPacketUtils.executeCraftingSteps(player, steps, network)) {
                FtbQuestSubmissionExecutor.submit(player, questId, network);
            } else {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.auto_craft_failed"));
            }
            return;
        }

        AsyncCraftChain chain = new AsyncCraftChain(player.getUUID(), player.getServer(), network,
                plan.graph());
        AsyncCraftManager.getInstance().submit(chain);
        chain.onDone(() -> {
            ServerPlayer current = player.getServer().getPlayerList().getPlayer(player.getUUID());
            if (current == null) return;
            if (chain.state() == AsyncCraftChain.State.COMPLETED) {
                FtbQuestSubmissionExecutor.submit(current, questId, network);
            } else {
                current.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.crafting_failed",
                        chain.abortReason()));
            }
        });
        player.sendSystemMessage(Component.translatable("rsi.ftb_quest.info.crafting_started",
                steps.size()));
    }

    private static List<CraftingResolver.ResolutionStep> projectSteps(QuestSubmissionPlan plan) {
        List<CraftingResolver.ResolutionStep> steps = new ArrayList<>();
        var nodes = plan.graph().nodesById();
        for (var nodeId : plan.graph().topologicalOrder()) {
            var node = nodes.get(nodeId);
            if (node == null) continue;
            steps.add(new CraftingResolver.ResolutionStep(node.recipeId(),
                    ModType.byId(node.modTypeId()), node.recipeTypeId(),
                    node.alternativeIds(), node.alternativeModTypeIds(), node.inferMode(),
                    node.executions(), node.syntheticInput(), node.syntheticOutput()));
        }
        return steps;
    }
}
