package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnostic;
import com.huanghuang.rsintegration.crafting.plan.ParallelPlanEstimator;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponseDraft;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketBatchDelegate;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper;
import com.huanghuang.rsintegration.storage.StorageNetworkDescriptor;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.util.ItemStackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 贸易站快速路径：整批计算成本，单节点兑换，复用标准响应复验与缓存流程。 */
final class CraftMarketPlanService {
    private CraftMarketPlanService() {}

    record TradeAvailability(long required, long available, boolean feasible) {}

    static TradeAvailability tradeAvailability(
            MarketRecipeWrapper recipe,
            Map<StackKey, ? extends Number> available, int repeatCount) {
        ItemStack cost = recipe.costItem();
        Ingredient costIngredient = Ingredient.of(cost);
        long matching = 0L;
        for (Map.Entry<StackKey, ? extends Number> entry : available.entrySet()) {
            long count = entry.getValue() == null ? 0L : entry.getValue().longValue();
            if (count > 0L && IngredientMatcher.test(costIngredient, entry.getKey().toStack())) {
                matching = SaturatingLongMath.add(matching, count);
            }
        }
        long required = (long) Math.max(1, cost.getCount()) * Math.max(1, repeatCount);
        return new TradeAvailability(required, matching, matching >= required);
    }

    static boolean tryBuildDirectPlan(
            ServerPlayer player, MarketRecipeWrapper recipe,
            ResourceLocation recipeId, int repeatCount, ItemStack targetOutput,
            @Nullable ItemStack clickedOutput, List<ScaledIngredientSpec> recipeSpecs,
            @Nullable ResourceLocation dimension, @Nullable BlockPos position,
            Map<StackKey, Long> available, PlanningSnapshot snapshot,
            PlanCache.Key cacheKey, @Nullable StorageReference storageReference,
            List<StorageNetworkDescriptor> storageNetworks, long planStartNanos,
            CraftPlanResultDispatcher.Sink sink) {
        TradeAvailability trade = tradeAvailability(recipe, available, repeatCount);
        if (!trade.feasible()) return false;
        // 沿用已应用材料锁的输入规格，保存实际支付的物品及 NBT，不能只缓存总数。
        DirectMaterialAllocator.Result allocation =
                DirectMaterialAllocator.allocateScaled(recipeSpecs, available);
        if (!allocation.feasible()) return false;

        ModType modType = ModType.FARMINGFORBLOCKHEADS_MARKET;
        CraftPlanGraph inputGraph =
                CraftIngredientPlanMath.directTerminalInputGraph(recipeSpecs, allocation);
        CraftPlanGraph graph = CraftTerminalSteps.composeEquivalentTerminalGraph(
                inputGraph,
                CraftTerminalSteps.backgroundPhysicalTerminalStep(
                        recipeId, modType, false, repeatCount),
                targetOutput, player.serverLevel());
        Map<Item, Long> itemAvailable = new HashMap<>();
        available.forEach((key, count) -> itemAvailable.merge(
                key.item(), count, SaturatingLongMath::add));
        PlanGraphView graphView = PlanGraphView.from(
                graph, ParallelPlanEstimator.estimate(player, graph, itemAvailable));
        List<PlanStep> steps = graphView.nodes().stream()
                .map(PlanGraphView.NodeView::asPlanStep).toList();
        Map<IngredientKey, PlanResponse.Availability> materials =
                GenericCraftPacket.directMaterialView(allocation, available);
        CraftMachineBindingSupport.PlanAvailability machines =
                CraftMachineBindingSupport.inspectPlanAvailability(
                        player, recipeId, modType, steps, dimension, position);
        boolean missingMachine = machines.missingExecutionBinding()
                || !machines.boundMachineTypes().contains(modType.id());
        boolean feasible = !missingMachine && !machines.allExecutionMachinesLeased();
        List<Component> warnings = new ArrayList<>(
                MarketBatchDelegate.getPlanWarnings(player, recipe, dimension, position));
        if (machines.allExecutionMachinesLeased()) {
            warnings.add(Component.translatable("rsi.plan.failure.machines_leased"));
        } else if (missingMachine) {
            warnings.add(Component.translatable("rsi.plan.failure.no_bound_machine"));
        }
        CraftPlanOutcomeResolver.Resolution outcome = CraftPlanOutcomeResolver.resolve(
                feasible, false, machines.allExecutionMachinesLeased(),
                machines.missingExecutionBinding(), missingMachine,
                false, false, false, false, missingMachine);
        List<IntegrationDiagnostic> diagnostics = outcome.diagnostic() == null
                ? List.of() : List.of(IntegrationDiagnostic.error(outcome.diagnostic()));
        PlanResponseDraft draft = new PlanResponseDraft(
                feasible, ItemStackUtils.registryId(targetOutput), targetOutput,
                steps, materials, List.of(), recipeId.toString(), modType.id(),
                dimension != null ? dimension.toString() : null,
                position != null ? position.getX() : 0,
                position != null ? position.getY() : 0,
                position != null ? position.getZ() : 0,
                warnings, repeatCount, null, null, null, 0L, false, false,
                machines.executionMachineSupportsGui(), null, machines.boundMachineTypes(),
                Map.of(), clickedOutput, graphView, missingMachine,
                List.of(), Map.of(), storageReference, storageNetworks,
                outcome.outcome(), diagnostics);
        ResourceKey<Level> planDimension = dimension == null
                ? player.serverLevel().dimension()
                : ResourceKey.create(Registries.DIMENSION, dimension);
        BlockPos planPosition = position == null ? player.blockPosition() : position;
        // 缓存完整的单节点兑换图；确认执行时不能再次追加终端或重新搜索配方。
        CraftPlanResponseFinalizer.submit(
                player, recipeId, snapshot.requestGeneration(), snapshot, draft,
                planDimension, planPosition, storageReference, cacheKey,
                null, graph, true, steps.size(), graph.nodes().size(),
                feasible, planStartNanos, sink);
        RSIntegrationMod.debug(
                "[RSI-Market] Direct plan: recipe={} repeat={} required={} available={}",
                recipeId, repeatCount, trade.required(), trade.available());
        return true;
    }
}
