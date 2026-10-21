package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.PerformanceMonitor;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponseDraft;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketBatchDelegate;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.util.ItemStackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** FfB 市场配方的库存判断和直接计划构建。 */
final class CraftMarketPlanService {
    private CraftMarketPlanService() {}

    record TradeAvailability(int required, int available, boolean feasible) {}

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
        int boundedRequired = (int) Math.min(Integer.MAX_VALUE, required);
        int boundedAvailable = SaturatingLongMath.clampToInt(matching);
        return new TradeAvailability(boundedRequired, boundedAvailable, matching >= required);
    }

    static boolean tryBuildDirectPlan(
            ServerPlayer player,
            MarketRecipeWrapper recipe,
            ResourceLocation recipeId, int repeatCount, ItemStack targetOutput,
            @Nullable ResourceLocation dimension, @Nullable BlockPos position,
            Map<StackKey, Long> available, PlanningSnapshot snapshot,
            PlanCache.Key cacheKey, long planStartNanos,
            CraftPlanResultDispatcher.Sink sink) {
        TradeAvailability trade = tradeAvailability(recipe, available, repeatCount);
        if (!trade.feasible()) return false;

        ItemStack cost = recipe.costItem();
        ModType modType = ModType.FARMINGFORBLOCKHEADS_MARKET;
        Map<IngredientKey, PlanResponse.Availability> materials = Map.of(
                IngredientKey.of(cost),
                new PlanResponse.Availability(trade.required(), trade.available()));
        List<PlanStep> steps = List.of(new PlanStep(
                recipeId, targetOutput, repeatCount, List.of(cost), Collections.emptyList(),
                modType, 0, false));
        List<Component> warnings = MarketBatchDelegate.getPlanWarnings(
                player, recipe, dimension, position);
        boolean supportsGui = false;
        if (dimension != null && position != null) {
            ServerLevel level = player.getServer().getLevel(
                    ResourceKey.create(Registries.DIMENSION, dimension));
            supportsGui = level != null && BindingEventHandler.supportsGuiAt(level, position);
        }
        Set<String> boundTypes = Set.of("vanilla_furnace", modType.id());
        PlanResponse response = new PlanResponseDraft(
                true,
                ItemStackUtils.registryId(targetOutput),
                targetOutput,
                steps,
                materials,
                List.of(),
                recipeId.toString(),
                modType.id(),
                dimension != null ? dimension.toString() : null,
                position != null ? position.getX() : 0,
                position != null ? position.getY() : 0,
                position != null ? position.getZ() : 0,
                warnings,
                repeatCount,
                null, null, null, 0L, false, false,
                supportsGui,
                null,
                boundTypes,
                Map.of(),
                null,
                null,
                false).toResponse();

        if (snapshot.requestGeneration() != 0L
                && !CraftPlanningRuntime.planRequests()
                .isCurrent(player.getUUID(), snapshot.requestGeneration())) {
            return true;
        }
        CraftPlanningRuntime.planCache().put(
                cacheKey, response, snapshot, System.nanoTime());
        sink.success(response, snapshot);
        PerformanceMonitor.recordPlanBuild(System.nanoTime() - planStartNanos, 1);
        RSIntegrationMod.debug(
                "[RSI-Market] Direct plan: recipe={} repeat={} required={} available={}",
                recipeId, repeatCount, trade.required(), trade.available());
        return true;
    }
}
