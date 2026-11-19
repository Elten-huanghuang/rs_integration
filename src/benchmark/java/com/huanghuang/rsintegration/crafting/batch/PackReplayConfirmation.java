package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningStateValidator;
import com.huanghuang.rsintegration.feature.resonance.bridge.ResonanceInventoryBridge;
import com.huanghuang.rsintegration.network.binding.MachineFavoritesSavedData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 使用回放库存替代网络读数，保留正式确认流程的材料复验与预览编号校验。 */
public final class PackReplayConfirmation {
    private PackReplayConfirmation() {}

    public static void verify(RecipeManager recipes, PlanResponse plan, PlanResponse displayed, long requestId,
                              CraftPlanGraph graph, Map<StackKey, Long> stock,
                              Map<ResourceLocation, ResourceLocation> overrides) {
        var player = mock(ServerPlayer.class);
        var playerId = UUID.randomUUID();
        var level = mock(ServerLevel.class);
        when(player.getUUID()).thenReturn(playerId);
        when(player.serverLevel()).thenReturn(level);
        when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getRecipeManager()).thenReturn(recipes);

        var recipeId = ResourceLocation.parse(plan.recipeId());
        Map<String, String> forced = new HashMap<>();
        overrides.forEach((item, recipe) -> forced.put(item.toString(), recipe.toString()));
        var output = displayed.targetResult();
        var key = CraftPlanCacheKeys.key(playerId, recipeId, forced, 1, output, null);
        var cache = CraftPlanningRuntime.planCache();
        var currentStock = new HashMap<>(stock);
        var favorites = mock(MachineFavoritesSavedData.class);
        when(favorites.getBlocked(playerId)).thenReturn(List.of());
        try (var storage = mockStatic(CraftPacketUtils.class);
             var materials = mockStatic(MaterialSources.class);
             var machines = mockStatic(MachineFavoritesSavedData.class);
             var resonance = mockStatic(ResonanceInventoryBridge.class)) {
            storage.when(() -> CraftPacketUtils.resolveEndpointForCraft(player, Level.OVERWORLD, BlockPos.ZERO))
                    .thenReturn(Optional.empty());
            materials.when(() -> MaterialSources.listAvailableForTypes(eq(player), isNull(), any(), any()))
                    .thenAnswer(unused -> Map.copyOf(currentStock));
            machines.when(() -> MachineFavoritesSavedData.get(null)).thenReturn(favorites);
            resonance.when(() -> ResonanceInventoryBridge.getViews(player)).thenReturn(List.of());
            var snapshot = new PlanningSnapshot(playerId, 1L, key.recipeRevision(), recipeId, stock, overrides,
                    new ImmutableRecipeGraph(Map.of()), PlanningStateValidator.networkFingerprint(null, stock),
                    PlanningStateValidator.bindingFingerprint(player, Level.OVERWORLD, null), true)
                    .withBindingAnchor(Level.OVERWORLD, null);
            cache.put(key, plan, snapshot, null, graph, System.nanoTime());
            cache.retainForRequest(playerId, requestId, snapshot);
            var packet = new GenericCraftPacket(recipeId, false, forced, null, null, 1,
                    false, null, output, requestId + 1, OutputDestination.RS_NETWORK).withConfirmedPreview(requestId);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            GenericCraftPacketCodec.WireData confirmation;
            try {
                packet.encode(buffer);
                confirmation = GenericCraftPacketCodec.read(buffer);
            } finally {
                buffer.release();
            }
            var material = plan.graph().edges().stream().filter(edge -> edge.source().initial())
                    .findFirst().orElseThrow().material();
            var materialKey = StackKey.of(material, true);
            Long removed = currentStock.remove(materialKey);
            if (removed == null || !confirm(player, recipeId, confirmation).rejected()
                    || cache.peekForRequest(key, requestId) == null) {
                throw new IllegalStateException("缺料时确认没有拒绝或保留原预览");
            }
            currentStock.put(materialKey, removed);
            var accepted = confirm(player, recipeId, confirmation);
            if (accepted.rejected() || accepted.entry() == null
                    || accepted.entry().plan() != plan || accepted.entry().resolvedGraph() != graph
                    || !confirm(player, recipeId, confirmation).rejected()) {
                throw new IllegalStateException("确认没有只消费一次原预览和原执行图");
            }
        } finally {
            cache.removePlayer(playerId);
        }
    }

    private static CraftPlanningApplicationService.ExecutionPlanLookup confirm(
            ServerPlayer player, ResourceLocation recipe, GenericCraftPacketCodec.WireData confirmation) {
        return CraftPlanningApplicationService.findValidatedExecutionPlan(player, recipe,
                confirmation.forcedRecipes(), confirmation.repeatCount(), confirmation.targetOutput(),
                null, null, null, null, confirmation.materialLocks(), confirmation.confirmedPreviewRequestId());
    }
}
