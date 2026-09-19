package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingPlanningTimeoutException;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import net.minecraftforge.network.PacketDistributor;
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
        preview(player, questId, 1);
    }

    public static void preview(ServerPlayer player, long questId, int repeatCount) {
        QuestSubmissionSnapshot snapshot = FtbQuestSubmissionScanner.findServer(player, questId);
        if (snapshot == null || !snapshot.eligible()) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.not_eligible"));
            return;
        }
        CraftStorageEndpoint endpoint = StorageRestockSupport.resolve(player).orElse(null);
        INetwork network = endpoint != null && "refinedstorage".equals(
                endpoint.session().reference().backendId().value())
                ? RSIntegrationNetwork.resolveNetworkFromPlayer(player) : null;
        QuestSubmissionPlan questPlan;
        try {
            questPlan = FtbQuestSubmissionPlanner.plan(player, snapshot, endpoint, network);
        } catch (CraftingPlanningTimeoutException timeout) {
            sendPlanningTimeout(player, repeatCount);
            return;
        }
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
                List.of(), Math.max(1, Math.min(repeatCount, 1024)), null, null, null, 0L,
                false, false, false, null, java.util.Set.of(), java.util.Map.of(),
                null, questPlan.graphView());
        BatchCraftNetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player), new PlanResponsePacket(plan));
    }

    public static void execute(ServerPlayer player, long questId) {
        execute(player, questId, 1);
    }

    public static void execute(ServerPlayer player, long questId, int repeatCount) {
        QuestSubmissionSnapshot snapshot = FtbQuestSubmissionScanner.findServer(player, questId);
        if (snapshot == null || !snapshot.eligible()) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.not_eligible"));
            return;
        }
        CraftStorageEndpoint endpoint = StorageRestockSupport.resolve(player).orElse(null);
        int requested = Math.max(1, Math.min(repeatCount, 1024));
        int executions = snapshot.repeatable() ? requested : 1;
        executeIteration(player, questId, endpoint, executions);
    }

    private static void executeIteration(ServerPlayer player, long questId,
                                         CraftStorageEndpoint endpoint, int remaining) {
        QuestSubmissionSnapshot snapshot = FtbQuestSubmissionScanner.findServer(player, questId);
        if (snapshot == null || !snapshot.eligible()) {
            player.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.not_eligible"));
            return;
        }
        INetwork network = endpoint != null && "refinedstorage".equals(
                endpoint.session().reference().backendId().value())
                ? RSIntegrationNetwork.resolveNetworkFromPlayer(player) : null;
        QuestSubmissionPlan plan;
        try {
            plan = FtbQuestSubmissionPlanner.plan(player, snapshot, endpoint, network);
        } catch (CraftingPlanningTimeoutException timeout) {
            player.sendSystemMessage(Component.translatable("rsi.plan.failure.planning_timeout"));
            return;
        }
        if (!plan.feasible()) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials",
                    CraftPacketUtils.formatMissingSummary(plan.missing())));
            return;
        }

        List<CraftingResolver.ResolutionStep> steps = projectSteps(plan);
        if (steps.isEmpty()) {
            if (FtbQuestSubmissionExecutor.submit(player, questId, endpoint)) {
                continueIfNeeded(player, questId, endpoint, remaining);
            }
            return;
        }

        if (steps.stream().allMatch(step -> step.modType() == ModType.GENERIC)) {
            if (CraftPacketUtils.executeCraftingSteps(player, steps, network, endpoint)) {
                if (FtbQuestSubmissionExecutor.submit(player, questId, endpoint)) {
                    continueIfNeeded(player, questId, endpoint, remaining);
                }
            } else {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.auto_craft_failed"));
            }
            return;
        }

        AsyncCraftChain chain = new AsyncCraftChain(player.getUUID(), player.getServer(), network,
                endpoint, plan.graph());
        AsyncCraftManager.getInstance().submit(chain);
        chain.onDone(() -> {
            ServerPlayer current = player.getServer().getPlayerList().getPlayer(player.getUUID());
            if (current == null) return;
            if (chain.state() == AsyncCraftChain.State.COMPLETED) {
                if (FtbQuestSubmissionExecutor.submit(current, questId, endpoint)) {
                    continueIfNeeded(current, questId, endpoint, remaining);
                }
            } else {
                current.sendSystemMessage(Component.translatable("rsi.ftb_quest.error.crafting_failed",
                        chain.abortReason()));
            }
        });
        player.sendSystemMessage(Component.translatable("rsi.ftb_quest.info.crafting_started",
                steps.size()));
    }

    private static void continueIfNeeded(ServerPlayer player, long questId,
                                         CraftStorageEndpoint endpoint, int remaining) {
        if (remaining > 1) {
            // Keep large repeat requests off the current call stack and let the
            // next submission observe FTB's repeat-reset state first.
            var server = player.getServer();
            var playerId = player.getUUID();
            server.execute(() -> {
                ServerPlayer current = server.getPlayerList().getPlayer(playerId);
                if (current != null) executeIteration(current, questId, endpoint, remaining - 1);
            });
        }
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

    /** Return a terminal response so the client cannot remain in a pending preview state. */
    private static void sendPlanningTimeout(ServerPlayer player, int repeatCount) {
        PlanResponse failure = new PlanResponse(false, "", ItemStack.EMPTY,
                List.of(), java.util.Map.of(), List.of(), "", null, null,
                0, 0, 0,
                List.of(Component.translatable("rsi.plan.failure.planning_timeout")),
                Math.max(1, Math.min(repeatCount, 1024)));
        BatchCraftNetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player), new PlanResponsePacket(failure));
    }
}
