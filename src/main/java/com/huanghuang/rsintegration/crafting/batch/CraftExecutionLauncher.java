package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.util.TextBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Map;

/** 启动已经包含终端的完整执行图。 */
final class CraftExecutionLauncher {
    private CraftExecutionLauncher() {}

    static void launchGraph(
            ServerPlayer player, CraftPlanGraph graph,
            @Nullable CraftStorageEndpoint storageEndpoint,
            ResourceLocation recipeId, Map<String, String> forcedRecipes,
            @Nullable ResourceLocation dimension, @Nullable BlockPos position,
            @Nullable ItemStack targetOutput, OutputDestination outputDestination,
            MachineSelectionMode machineSelectionMode,
            Map<String, ItemStack> materialLocks) {
        AsyncCraftChain chain = new AsyncCraftChain(
                player.getUUID(), player.getServer(), storageEndpoint, graph);
        RSIntegrationMod.LOGGER.info(
                "[RSI-Craft] launch craftId={} graphNodes={} executor=graph terminalEmbedded=true",
                chain.getCraftId(), graph.topologicalOrder().size());
        chain.setTargetOutput(targetOutput);
        chain.setSupplementalPlanningPreferences(forcedRecipes, materialLocks);
        chain.setOutputDestination(outputDestination);
        chain.setMachineSelection(
                recipeId, machineSelectionMode, dimension, position);
        var server = player.getServer();
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
