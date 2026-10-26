package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.util.TextBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Consumer;

/** 提交完整执行图，并在当前 tick 内尽可能立即推进。 */
public final class CraftExecutionService {
    private CraftExecutionService() {}

    public static AsyncCraftChain launchGraph(
            ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            CraftPlanGraph graph,
            ResourceLocation recipeId,
            @Nullable IntegrationResourceKey targetResource,
            @Nullable ItemStack targetOutput,
            OutputDestination outputDestination,
            Consumer<AsyncCraftChain> completionWiring
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(outputDestination, "outputDestination");
        Objects.requireNonNull(completionWiring, "completionWiring");

        ModType executionType = graph.nodes().stream()
                .map(node -> ModType.findById(node.modTypeId()))
                .filter(Objects::nonNull)
                .filter(type -> type != ModType.GENERIC)
                .findFirst()
                .orElse(ModType.GENERIC);
        ExecutionMetrics.record(
                ExecutionMetrics.Reason.GRAPH_PLAN,
                recipeId,
                executionType
        );
        RSIntegrationMod.LOGGER.info(
                "[RSI-Craft] execution graph submit recipe={} modType={} graphNodes={}",
                recipeId, executionType.id(), graph.nodes().size()
        );

        AsyncCraftChain chain = new AsyncCraftChain(
                player.getUUID(), player.getServer(), storageEndpoint, graph);
        return submit(
                player, chain, graph.nodes().size(), recipeId,
                targetResource, targetOutput,
                outputDestination, completionWiring
        );
    }

    private static AsyncCraftChain submit(
            ServerPlayer player, AsyncCraftChain chain, int displayedSteps,
            ResourceLocation recipeId,
            @Nullable IntegrationResourceKey targetResource,
            @Nullable ItemStack targetOutput, OutputDestination outputDestination,
            Consumer<AsyncCraftChain> completionWiring) {
        if (targetResource != null) {
            chain.setTargetResource(targetResource);
        } else {
            chain.setTargetOutput(targetOutput);
        }
        chain.setOutputDestination(outputDestination);
        completionWiring.accept(chain);
        boolean completed = AsyncCraftManager.getInstance().submitAndAdvance(
                chain, player.getServer());
        if (!completed) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-Craft] execution chain deferred craftId={} recipe={} state={} steps={}",
                    chain.getCraftId(), recipeId, chain.state(), displayedSteps);
            player.sendSystemMessage(TextBuilder.translate(
                    outputDestination == OutputDestination.PLAYER_INVENTORY
                            ? "rsi.async.chain_started_player"
                            : "rsi.async.chain_started",
                    displayedSteps).build());
        }
        return chain;
    }
}
