package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 启动已经裁剪并验证过的材料准备图。 */
final class CraftPreparationLauncher {
    private CraftPreparationLauncher() {}

    static void launch(
            ServerPlayer player, CraftPlanGraph graph, ResourceLocation recipeId,
            CraftStorageEndpoint storageEndpoint,
            @Nullable ResourceLocation dimension,
            @Nullable BlockPos position, MachineSelectionMode machineSelectionMode,
            Component products, Component stillMissing, Map<String, ItemStack> materialLocks,
            @Nullable Map<ResourceLocation, ResourceLocation> forcedOverrides) {
        AsyncCraftChain chain = new AsyncCraftChain(
                player.getUUID(), player.getServer(), storageEndpoint, graph);
        chain.setOutputDestination(OutputDestination.RS_NETWORK);
        Map<String, String> forcedRecipes = new HashMap<>();
        if (forcedOverrides != null) {
            forcedOverrides.forEach((output, forcedRecipe) ->
                    forcedRecipes.put(output.toString(), forcedRecipe.toString()));
        }
        chain.setSupplementalPlanningPreferences(forcedRecipes, materialLocks);
        chain.setMachineSelection(recipeId, machineSelectionMode, dimension, position);
        UUID playerId = player.getUUID();
        MinecraftServer server = player.getServer();
        chain.onDone(() -> {
            ServerPlayer online = server == null
                    ? null : server.getPlayerList().getPlayer(playerId);
            if (online == null) return;
            online.sendSystemMessage(Component.translatable(
                    chain.state() == AsyncCraftChain.State.COMPLETED
                            ? "rsi.generic.preparation.completed"
                            : "rsi.generic.preparation.failed_detail",
                    products, stillMissing));
        });
        boolean completed = AsyncCraftManager.getInstance().submitAndAdvance(
                chain, player.getServer());
        if (!completed) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.preparation.started", products, stillMissing));
        }
    }
}
