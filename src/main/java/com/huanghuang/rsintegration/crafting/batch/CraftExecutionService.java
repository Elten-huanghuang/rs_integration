package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.util.TextBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
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
        return launchGraph(
                player, storageEndpoint, graph, recipeId, targetResource, targetOutput,
                outputDestination, MachineSelectionMode.AUTO, null, null, Map.of(), Map.of(),
                0L, completionWiring);
    }

    /**
     * 提交完整执行图的统一入口。所有任务在提交前完成目标、机器选择、材料锁和完成回调配置。
     */
    public static AsyncCraftChain launchGraph(
            ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            CraftPlanGraph graph,
            ResourceLocation recipeId,
            @Nullable IntegrationResourceKey targetResource,
            @Nullable ItemStack targetOutput,
            OutputDestination outputDestination,
            MachineSelectionMode machineSelectionMode,
            @Nullable ResourceLocation dimension,
            @Nullable BlockPos position,
            Map<String, String> forcedRecipes,
            Map<String, ItemStack> materialLocks,
            long confirmedPreviewRequestId,
            Consumer<AsyncCraftChain> completionWiring
    ) {
        return launchGraph(
                player, storageEndpoint, graph, recipeId, targetResource, targetOutput,
                outputDestination, machineSelectionMode, dimension, position, forcedRecipes,
                materialLocks, confirmedPreviewRequestId, completionWiring, null);
    }

    private static AsyncCraftChain launchGraph(
            ServerPlayer player,
            @Nullable CraftStorageEndpoint storageEndpoint,
            CraftPlanGraph graph,
            ResourceLocation recipeId,
            @Nullable IntegrationResourceKey targetResource,
            @Nullable ItemStack targetOutput,
            OutputDestination outputDestination,
            MachineSelectionMode machineSelectionMode,
            @Nullable ResourceLocation dimension,
            @Nullable BlockPos position,
            Map<String, String> forcedRecipes,
            Map<String, ItemStack> materialLocks,
            long confirmedPreviewRequestId,
            Consumer<AsyncCraftChain> completionWiring,
            @Nullable Component startedMessage
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(outputDestination, "outputDestination");
        Objects.requireNonNull(machineSelectionMode, "machineSelectionMode");
        Objects.requireNonNull(forcedRecipes, "forcedRecipes");
        Objects.requireNonNull(materialLocks, "materialLocks");
        Objects.requireNonNull(completionWiring, "completionWiring");
        if (confirmedPreviewRequestId < 0L) {
            throw new IllegalArgumentException("negative preview request");
        }

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
                outputDestination, machineSelectionMode, dimension, position,
                forcedRecipes, materialLocks, confirmedPreviewRequestId,
                startedMessage, completionWiring
        );
    }

    /**
     * 提交材料准备图。准备任务与普通执行使用相同的链构造和推进逻辑，只替换通知文案。
     */
    public static AsyncCraftChain launchPreparation(
            ServerPlayer player,
            CraftPlanGraph graph,
            ResourceLocation recipeId,
            CraftStorageEndpoint storageEndpoint,
            @Nullable ResourceLocation dimension,
            @Nullable BlockPos position,
            MachineSelectionMode machineSelectionMode,
            Component products,
            Component stillMissing,
            Map<String, ItemStack> materialLocks,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides
    ) {
        Objects.requireNonNull(products, "products");
        Objects.requireNonNull(stillMissing, "stillMissing");
        Map<String, String> forcedRecipes = new HashMap<>();
        if (forcedOverrides != null) {
            forcedOverrides.forEach((output, forcedRecipe) ->
                    forcedRecipes.put(output.toString(), forcedRecipe.toString()));
        }
        MinecraftServer server = player.getServer();
        return launchGraph(
                player, storageEndpoint, graph, recipeId, null, null,
                OutputDestination.RS_NETWORK, machineSelectionMode, dimension, position,
                forcedRecipes, materialLocks, 0L,
                chain -> {
                    chain.onDone(() -> {
                        ServerPlayer online = server == null
                                ? null : server.getPlayerList().getPlayer(player.getUUID());
                        if (online == null) return;
                        online.sendSystemMessage(Component.translatable(
                                chain.state() == AsyncCraftChain.State.COMPLETED
                                        ? "rsi.generic.preparation.completed"
                                        : "rsi.generic.preparation.failed_detail",
                                products, stillMissing));
                    });
                },
                Component.translatable("rsi.generic.preparation.started", products, stillMissing));
    }

    private static AsyncCraftChain submit(
            ServerPlayer player, AsyncCraftChain chain, int displayedSteps,
            ResourceLocation recipeId,
            @Nullable IntegrationResourceKey targetResource,
            @Nullable ItemStack targetOutput, OutputDestination outputDestination,
            MachineSelectionMode machineSelectionMode,
            @Nullable ResourceLocation dimension,
            @Nullable BlockPos position,
            Map<String, String> forcedRecipes,
            Map<String, ItemStack> materialLocks,
            long confirmedPreviewRequestId,
            @Nullable Component startedMessage,
            Consumer<AsyncCraftChain> completionWiring) {
        if (targetResource != null) {
            chain.setTargetResource(targetResource);
        } else {
            chain.setTargetOutput(targetOutput);
        }
        chain.setOutputDestination(outputDestination);
        chain.setConfirmedPreviewRequestId(confirmedPreviewRequestId);
        chain.setSupplementalPlanningPreferences(forcedRecipes, materialLocks);
        chain.setMachineSelection(recipeId, machineSelectionMode, dimension, position);
        completionWiring.accept(chain);
        boolean completed = AsyncCraftManager.getInstance().submitAndAdvance(
                chain, player.getServer());
        if (!completed) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-Craft] execution chain deferred craftId={} recipe={} state={} steps={}",
                    chain.getCraftId(), recipeId, chain.state(), displayedSteps);
            if (startedMessage != null) {
                player.sendSystemMessage(startedMessage);
            } else {
                player.sendSystemMessage(TextBuilder.translate(
                        outputDestination == OutputDestination.PLAYER_INVENTORY
                                ? "rsi.async.chain_started_player"
                                : "rsi.async.chain_started",
                        displayedSteps).build());
            }
        }
        return chain;
    }
}
