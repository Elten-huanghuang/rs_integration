package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnostic;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.util.ItemStackUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/** 由持久化偏好直接生成的终止响应，不进入递归规划。 */
final class CraftPlanPreferenceResponses {
    private CraftPlanPreferenceResponses() {}

    static PlanResponse excluded(
            ResourceLocation recipeId, ItemStack target, int repeatCount,
            Map<StackKey, Long> available) {
        long stored = availableFor(target, available);
        return new PlanResponse(
                false, ItemStackUtils.registryId(target), target, List.of(),
                Map.of(IngredientKey.of(target.copyWithCount(1)),
                        new PlanResponse.Availability(repeatCount, stored)),
                List.of(target.getDescriptionId()), recipeId.toString(),
                List.of(Component.translatable(
                        "rsi.plan.failure.recipe_excluded")),
                repeatCount, PlanOutcome.NO_RECIPE,
                List.of(IntegrationDiagnostic.error(
                        IntegrationDiagnosticCode.RECIPE_EXCLUDED)));
    }

    static PlanResponse noRecipe(
            ResourceLocation recipeId, ItemStack target, int repeatCount,
            Map<StackKey, Long> available) {
        long stored = availableFor(target, available);
        return new PlanResponse(
                false, ItemStackUtils.registryId(target), target, List.of(),
                Map.of(IngredientKey.of(target.copyWithCount(1)),
                        new PlanResponse.Availability(repeatCount, stored)),
                List.of(target.getDescriptionId()), recipeId.toString(),
                List.of(Component.translatable(
                        "rsi.plan.failure.no_recipe")),
                repeatCount, PlanOutcome.NO_RECIPE,
                List.of(IntegrationDiagnostic.error(
                        IntegrationDiagnosticCode.MISSING_MATERIALS)));
    }

    private static long availableFor(
            ItemStack target, Map<StackKey, Long> available) {
        return available.entrySet().stream()
                .filter(entry -> MaterialMatcher.sameRuntimeFragment(
                        entry.getKey().toStack(), target))
                .mapToLong(Map.Entry::getValue)
                .reduce(0L, SaturatingLongMath::add);
    }
}
