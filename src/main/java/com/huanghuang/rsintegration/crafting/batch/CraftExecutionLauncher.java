package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.ChainRepeatController;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.util.TextBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 启动扁平步骤或完整执行图，并在完成后接续剩余次数。 */
final class CraftExecutionLauncher {
    private CraftExecutionLauncher() {}

    @FunctionalInterface
    interface RepeatRequester {
        void schedule(ServerPlayer player, int remaining);
    }

    static void launchFlat(
            ServerPlayer player, List<ResolutionStep> steps,
            LegacyExecutionMetrics.Reason legacyReason,
            @Nullable CraftStorageEndpoint storageEndpoint,
            ResourceLocation recipeId, Map<String, String> forcedRecipes,
            @Nullable ResourceLocation dimension, @Nullable BlockPos position,
            boolean inferMode, @Nullable ItemStack baseItem,
            @Nullable ItemStack targetOutput, OutputDestination outputDestination,
            MachineSelectionMode machineSelectionMode,
            Map<String, ItemStack> materialLocks, RepeatRequester repeatRequester) {
        UUID playerId = player.getUUID();
        var server = player.getServer();
        StorageReference repeatReference = storageEndpoint == null
                ? null : storageEndpoint.session().reference();
        // 解析步骤已包含请求次数对应的全部物理执行数，不能再次乘中间步骤。
        CraftExecutionService.launch(
                player, storageEndpoint, steps, legacyReason, recipeId,
                targetOutput, outputDestination, runningChain -> {
                    runningChain.setSupplementalPlanningPreferences(
                            forcedRecipes, materialLocks);
                    runningChain.setMachineSelection(
                            recipeId, machineSelectionMode, dimension, position);
                    runningChain.onDone(() -> ChainRepeatController.scheduleNext(
                            runningChain, server, playerId, 1,
                            runningChain.getMachineCount(),
                            repeatRequester::schedule));
                });
    }

    static void launchGraph(
            ServerPlayer player, CraftPlanGraph graph, ResolutionStep terminalStep,
            @Nullable CraftStorageEndpoint storageEndpoint,
            ResourceLocation recipeId, Map<String, String> forcedRecipes,
            @Nullable ResourceLocation dimension, @Nullable BlockPos position,
            boolean inferMode, @Nullable ItemStack baseItem,
            @Nullable ItemStack targetOutput, OutputDestination outputDestination,
            MachineSelectionMode machineSelectionMode,
            Map<String, ItemStack> materialLocks, RepeatRequester repeatRequester) {
        AsyncCraftChain chain = new AsyncCraftChain(
                player.getUUID(), player.getServer(), storageEndpoint, graph);
        if (!chain.isGraphExecution()) {
            ModType legacyType = graph.nodes().stream()
                    .map(node -> ModType.byId(node.modTypeId()))
                    .filter(type -> type.flatExecutionReason() != null)
                    .findFirst().orElse(terminalStep.modType());
            LegacyExecutionMetrics.record(
                    LegacyExecutionMetrics.Reason.MOD_REQUIRES_FLAT_EXECUTION,
                    recipeId, legacyType);
        }
        RSIntegrationMod.LOGGER.info(
                "[RSI-Craft] launch craftId={} graphNodes={} terminalRecipe={} executor={} terminalEmbedded=true",
                chain.getCraftId(), graph.topologicalOrder().size(), terminalStep.recipeId(),
                chain.isGraphExecution() ? "graph" : "flat");
        chain.setTargetOutput(targetOutput);
        chain.setSupplementalPlanningPreferences(forcedRecipes, materialLocks);
        chain.setOutputDestination(outputDestination);
        chain.setMachineSelection(
                recipeId, machineSelectionMode, dimension, position);
        UUID playerId = player.getUUID();
        var server = player.getServer();
        chain.onDone(() -> ChainRepeatController.scheduleNext(
                chain, server, playerId, 1, chain.getMachineCount(),
                repeatRequester::schedule));
        boolean completed = AsyncCraftManager.getInstance().submitAndAdvance(chain, server);
        if (!completed) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-Craft] execution graph deferred craftId={} recipe={} state={} steps={}",
                    chain.getCraftId(), recipeId, chain.state(), chain.stepsCount());
            player.sendSystemMessage(TextBuilder.translate(
                    outputDestination == OutputDestination.PLAYER_INVENTORY
                            ? "rsi.async.chain_started_player"
                            : "rsi.async.chain_started",
                    chain.stepsCount()).build());
        }
    }
}
