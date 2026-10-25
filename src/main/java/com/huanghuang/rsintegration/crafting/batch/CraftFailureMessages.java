package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftingPlanningTimeoutException;
import com.huanghuang.rsintegration.crafting.RSICraftException;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnostic;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector.RecipeGraphUnavailableException;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.List;

/** 服务端失败到玩家可读文案/稳定诊断码的纯转换。 */
final class CraftFailureMessages {
    private CraftFailureMessages() {}

    static Component build(Throwable failure, ResourceLocation recipeId) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RecipeGraphUnavailableException) {
                return Component.translatable("rsi.plan.failure.catalog_loading");
            }
        }
        if (hasReason(failure, RSICraftException.Reason.NETWORK_UNAVAILABLE)) {
            return Component.translatable("rsi.generic.error.network_unavailable");
        }
        if (containsPlanningTimeout(failure)) {
            return Component.translatable("rsi.plan.failure.planning_timeout");
        }
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
        return Component.translatable(
                "rsi.generic.error.craft_failed", recipeId + " - " + detail);
    }

    static Component pureMissingSummary(
            List<ImmutableRecipeGraph.IngredientRef> demands) {
        if (demands == null || demands.isEmpty()) {
            return Component.translatable("rsi.plan.failure.missing_materials");
        }
        MutableComponent result = Component.empty();
        int shown = 0;
        for (ImmutableRecipeGraph.IngredientRef demand : demands) {
            if (demand == null || demand.alternatives().isEmpty()) continue;
            if (shown++ > 0) result.append(", ");
            result.append(pureAlternatives(demand.alternatives()));
            result.append(" x").append(String.valueOf(Math.max(1, demand.count())));
            if (shown >= 8) {
                int remaining = demands.size() - shown;
                if (remaining > 0) {
                    result.append(" ").append(Component.translatable(
                            "rsi.plan.missing_more", remaining));
                }
                break;
            }
        }
        return result;
    }

    static IntegrationDiagnostic purePreviewDiagnostic(PureRecipePlanner.Result result) {
        return IntegrationDiagnostic.error(
                result.status() == PureRecipePlanner.Status.TIME_LIMIT
                        ? IntegrationDiagnosticCode.PLANNING_TIMEOUT
                        : IntegrationDiagnosticCode.COMPLEXITY_LIMIT);
    }

    private static MutableComponent pureAlternatives(
            List<ImmutableRecipeGraph.MaterialRef> alternatives) {
        MutableComponent result = Component.empty();
        int shown = 0;
        for (ImmutableRecipeGraph.MaterialRef material : alternatives) {
            if (material == null || material.itemId() == null) continue;
            if (shown++ > 0) result.append(" / ");
            Item item = BuiltInRegistries.ITEM.getOptional(material.itemId()).orElse(null);
            Component name = item == null
                    ? Component.literal(material.itemId().toString())
                    : CraftPacketUtils.missingMaterialName(item.getDescriptionId());
            result.append(name);
            String nbt = material.nbt();
            if (material.runtimeNbt()) {
                result.append(" [NBT]");
            } else if (nbt != null && !nbt.isBlank()) {
                String compact = nbt.replace('\n', ' ');
                if (compact.length() > 120) compact = compact.substring(0, 117) + "...";
                result.append(" [").append(compact).append("]");
            }
            if (shown >= 4 && alternatives.size() > shown) {
                result.append(" ").append(Component.translatable(
                        "rsi.plan.missing_more", alternatives.size() - shown));
                break;
            }
        }
        return result;
    }

    private static boolean containsPlanningTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof CraftingPlanningTimeoutException) return true;
        }
        return false;
    }

    private static boolean hasReason(
            Throwable failure, RSICraftException.Reason reason) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof RSICraftException exception
                    && exception.reason() == reason) return true;
        }
        return false;
    }
}
