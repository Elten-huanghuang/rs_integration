package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.util.TextBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 统一提交扁平步骤和图步骤，并在当前 tick 内尽可能立即推进。 */
public final class CraftExecutionService {
    private CraftExecutionService() {}

    public static AsyncCraftChain launch(
            ServerPlayer player, @Nullable CraftStorageEndpoint storageEndpoint,
            List<CraftingResolver.ResolutionStep> steps,
            LegacyExecutionMetrics.Reason reason, ResourceLocation recipeId,
            @Nullable ItemStack targetOutput, OutputDestination outputDestination,
            Consumer<AsyncCraftChain> completionWiring) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(outputDestination, "outputDestination");
        Objects.requireNonNull(completionWiring, "completionWiring");

        List<CraftingResolver.ResolutionStep> chainSteps = List.copyOf(steps);
        ModType legacyType = chainSteps.stream()
                .map(CraftingResolver.ResolutionStep::modType)
                .filter(type -> type != ModType.GENERIC)
                .findFirst().orElse(ModType.GENERIC);
        LegacyExecutionMetrics.record(reason, recipeId, legacyType);
        RSIntegrationMod.LOGGER.info(
                "[RSI-Craft] execution chain submit recipe={} reason={} modType={} steps={}",
                recipeId, reason, legacyType.id(), chainSteps.size());

        AsyncCraftChain chain = new AsyncCraftChain(
                player.getUUID(), player.getServer(), storageEndpoint, chainSteps);
        return submit(player, chain, chainSteps.size(), recipeId, targetOutput,
                outputDestination, completionWiring);
    }

    public static AsyncCraftChain launchIncompleteGraph(
            ServerPlayer player, @Nullable CraftStorageEndpoint storageEndpoint,
            CraftPlanGraph inputGraph,
            CraftingResolver.ResolutionStep terminalStep, int repeatCount,
            LegacyExecutionMetrics.Reason reason, ResourceLocation recipeId,
            @Nullable ItemStack targetOutput, OutputDestination outputDestination,
            Consumer<AsyncCraftChain> completionWiring) {
        Objects.requireNonNull(inputGraph, "inputGraph");
        Objects.requireNonNull(terminalStep, "terminalStep");
        LegacyExecutionMetrics.record(reason, recipeId, terminalStep.modType());
        RSIntegrationMod.LOGGER.info(
                "[RSI-Craft] execution graph submit recipe={} reason={} modType={} graphNodes={}",
                recipeId, reason, terminalStep.modType().id(), inputGraph.nodes().size());
        AsyncCraftChain chain = new AsyncCraftChain(player.getUUID(), player.getServer(),
                storageEndpoint, inputGraph, terminalStep, repeatCount);
        return submit(player, chain, chain.stepsCount(), recipeId, targetOutput,
                outputDestination, completionWiring);
    }

    private static AsyncCraftChain submit(
            ServerPlayer player, AsyncCraftChain chain, int displayedSteps,
            ResourceLocation recipeId,
            @Nullable ItemStack targetOutput, OutputDestination outputDestination,
            Consumer<AsyncCraftChain> completionWiring) {
        chain.setTargetOutput(targetOutput);
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
