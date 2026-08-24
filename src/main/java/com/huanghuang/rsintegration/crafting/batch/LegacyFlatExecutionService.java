package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.util.TextBuilder;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Owns the standard launch lifecycle for an explicitly classified flat adapter. */
public final class LegacyFlatExecutionService {
    private LegacyFlatExecutionService() {}

    public static AsyncCraftChain launch(
            ServerPlayer player, INetwork network,
            List<CraftingResolver.ResolutionStep> steps,
            LegacyExecutionMetrics.Reason reason, ResourceLocation recipeId,
            @Nullable ItemStack targetOutput, OutputDestination outputDestination,
            Consumer<AsyncCraftChain> completionWiring) {
        return launch(player, network, null, steps, reason, recipeId, targetOutput,
                outputDestination, completionWiring);
    }

    public static AsyncCraftChain launch(
            ServerPlayer player, @Nullable INetwork network,
            @Nullable CraftStorageEndpoint storageEndpoint,
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
                "[RSI-Craft] legacy flat launch recipe={} reason={} modType={} steps={}",
                recipeId, reason, legacyType.id(), chainSteps.size());

        AsyncCraftChain chain = new AsyncCraftChain(
                player.getUUID(), player.getServer(), network, storageEndpoint, chainSteps);
        return submit(player, chain, chainSteps.size(), targetOutput,
                outputDestination, completionWiring);
    }

    public static AsyncCraftChain launchIncompleteGraph(
            ServerPlayer player, INetwork network, CraftPlanGraph inputGraph,
            CraftingResolver.ResolutionStep terminalStep, int repeatCount,
            LegacyExecutionMetrics.Reason reason, ResourceLocation recipeId,
            @Nullable ItemStack targetOutput, OutputDestination outputDestination,
            Consumer<AsyncCraftChain> completionWiring) {
        return launchIncompleteGraph(player, network, null, inputGraph, terminalStep, repeatCount,
                reason, recipeId, targetOutput, outputDestination, completionWiring);
    }

    public static AsyncCraftChain launchIncompleteGraph(
            ServerPlayer player, @Nullable INetwork network,
            @Nullable CraftStorageEndpoint storageEndpoint, CraftPlanGraph inputGraph,
            CraftingResolver.ResolutionStep terminalStep, int repeatCount,
            LegacyExecutionMetrics.Reason reason, ResourceLocation recipeId,
            @Nullable ItemStack targetOutput, OutputDestination outputDestination,
            Consumer<AsyncCraftChain> completionWiring) {
        Objects.requireNonNull(inputGraph, "inputGraph");
        Objects.requireNonNull(terminalStep, "terminalStep");
        LegacyExecutionMetrics.record(reason, recipeId, terminalStep.modType());
        RSIntegrationMod.LOGGER.info(
                "[RSI-Craft] legacy incomplete-graph launch recipe={} reason={} modType={} graphNodes={}",
                recipeId, reason, terminalStep.modType().id(), inputGraph.nodes().size());
        AsyncCraftChain chain = new AsyncCraftChain(player.getUUID(), player.getServer(), network,
                storageEndpoint, inputGraph, terminalStep, repeatCount);
        return submit(player, chain, chain.stepsCount(), targetOutput,
                outputDestination, completionWiring);
    }

    private static AsyncCraftChain submit(
            ServerPlayer player, AsyncCraftChain chain, int displayedSteps,
            @Nullable ItemStack targetOutput, OutputDestination outputDestination,
            Consumer<AsyncCraftChain> completionWiring) {
        chain.setTargetOutput(targetOutput);
        chain.setOutputDestination(outputDestination);
        completionWiring.accept(chain);
        AsyncCraftManager.getInstance().submit(chain);
        player.sendSystemMessage(TextBuilder.translate(
                outputDestination == OutputDestination.PLAYER_INVENTORY
                        ? "rsi.async.chain_started_player"
                        : "rsi.async.chain_started",
                displayedSteps).build());
        return chain;
    }
}
