package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.plan.MachineCandidateView;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.plan.PlanWarnings;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.magic.MagicResourceCost;
import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.mods.goety.magic.SoulPlanPreview;
import com.huanghuang.rsintegration.mods.goety.magic.SoulPlanWarnings;
import com.huanghuang.rsintegration.mods.goety.GoetyBatchDelegate;
import com.huanghuang.rsintegration.mods.goety.GoetySoulTotemCrafting;
import com.huanghuang.rsintegration.mods.summoningrituals.SummoningRitualAltarBatchDelegate;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 汇总机器、魔力、灵魂、仪式和运行时容量前置条件。 */
final class CraftPlanPrerequisiteInspector {
    private static final int MAX_GOETY_CANDIDATE_WARNINGS = 32;

    private CraftPlanPrerequisiteInspector() {}

    record Result(
            boolean feasible,
            boolean blockingPrerequisiteFailure,
            List<Component> warnings,
            List<MachineCandidateView> machineCandidates,
            boolean noReadyMachine) {}

    static Result inspect(
            ServerPlayer player, Recipe<?> recipe, ResourceLocation recipeId,
            int repeatCount, @Nullable CraftStorageEndpoint planningEndpoint,
            @Nullable CraftPlanGraph planGraph, List<PlanStep> steps,
            Map<StackKey, Long> available,
            @Nullable ModType recipeModType, boolean feasible,
            boolean blockingPrerequisiteFailure) {
        List<Component> warnings = new ArrayList<>();
        Map<MagicResourceKind, Long> plannedCosts =
                new EnumMap<>(MagicResourceKind.class);
        if (planGraph != null) {
            for (MagicResourceCost cost : planGraph.magicCosts()) {
                long total = plannedCosts.getOrDefault(cost.kind(), 0L);
                plannedCosts.put(cost.kind(),
                        SaturatingLongMath.add(
                                total, cost.amountPerOperation()));
            }
        }

        long totalBotaniaMana =
                plannedCosts.getOrDefault(MagicResourceKind.MANA, -1L);
        if (totalBotaniaMana < 0L) {
            totalBotaniaMana = 0L;
            for (PlanStep step : steps) {
                Recipe<?> stepRecipe = player.serverLevel().getRecipeManager()
                        .byKey(step.recipeId()).orElse(null);
                int manaPerBatch = stepRecipe == null
                        ? 0 : PlanWarnings.botaniaManaCost(stepRecipe);
                if (manaPerBatch > 0) {
                    totalBotaniaMana = Math.min(Long.MAX_VALUE,
                            totalBotaniaMana
                                    + (long) manaPerBatch
                                    * Math.max(1, step.batches()));
                }
            }
        }
        if (totalBotaniaMana > 0) {
            warnings.add(Component.translatable(
                    "rsi.botania.warn.total_mana_required",
                    totalBotaniaMana));
        }

        long totalArsSource =
                plannedCosts.getOrDefault(MagicResourceKind.SOURCE, -1L);
        if (totalArsSource < 0L) {
            totalArsSource = 0L;
            for (PlanStep step : steps) {
                Recipe<?> stepRecipe = player.serverLevel().getRecipeManager()
                        .byKey(step.recipeId()).orElse(null);
                int sourcePerBatch = stepRecipe == null
                        ? 0 : PlanWarnings.arsSourceCost(stepRecipe);
                if (sourcePerBatch > 0) {
                    long stepTotal = (long) sourcePerBatch
                            * Math.max(1, step.batches());
                    totalArsSource = SaturatingLongMath.add(
                            totalArsSource, stepTotal);
                }
            }
        }
        if (totalArsSource > 0) {
            warnings.add(Component.translatable(
                    "rsi.ars_nouveau.warn.total_source_required",
                    String.format("%,d", totalArsSource)));
        }

        long totalGoetyRitualSouls =
                plannedCosts.getOrDefault(MagicResourceKind.SOUL, -1L);
        long totalGoetyTotemSouls = 0L;
        if (totalGoetyRitualSouls < 0L) {
            totalGoetyRitualSouls = 0L;
        }
        for (PlanStep step : steps) {
            Recipe<?> stepRecipe = player.serverLevel().getRecipeManager()
                    .byKey(step.recipeId()).orElse(null);
            if (stepRecipe == null) continue;
            long executions = Math.max(1, step.batches());
            if (plannedCosts.getOrDefault(
                    MagicResourceKind.SOUL, -1L) < 0L) {
                long ritualCost = (long) PlanWarnings.goetyRitualSoulCost(
                        stepRecipe) * executions;
                totalGoetyRitualSouls = SaturatingLongMath.add(
                        totalGoetyRitualSouls, ritualCost);
            }
            long totemCost = (long) PlanWarnings.goetyTotemSoulCost(
                    stepRecipe) * executions;
            totalGoetyTotemSouls = SaturatingLongMath.add(
                    totalGoetyTotemSouls, totemCost);
        }
        if (totalGoetyRitualSouls > 0) {
            // 预览与执行共用 SoulPlanPreview，避免两边独立估算产生偏差。
            SoulPlanPreview.Estimate estimate = SoulPlanPreview.estimate(
                    player, planningEndpoint, (int) Math.min(
                            Integer.MAX_VALUE, totalGoetyRitualSouls));
            RSIntegrationMod.debug(
                    "[RSI-Goety] soul plan recipe={} class={} batches={} total={} endpoint={} source={} available={}",
                    recipeId, recipe.getClass().getName(),
                    Math.max(1, repeatCount), totalGoetyRitualSouls,
                    planningEndpoint == null ? "none"
                            : planningEndpoint.session().reference()
                            .backendId().value(),
                    estimate.source(), estimate.legacyAvailable());
            Component warning = SoulPlanWarnings.describe(
                    player, planningEndpoint, (int) Math.min(
                            Integer.MAX_VALUE, totalGoetyRitualSouls));
            if (warning != null) warnings.add(warning);
        }
        if (totalGoetyTotemSouls > 0) {
            int availableTotemSouls =
                    GoetySoulTotemCrafting.maxAvailableSouls(available);
            if (availableTotemSouls < totalGoetyTotemSouls) {
                feasible = false;
                warnings.add(Component.translatable(
                        "rsi.goety.warn.totem_souls_insufficient",
                        String.format("%,d", totalGoetyTotemSouls),
                        String.format("%,d", availableTotemSouls)));
            } else {
                warnings.add(Component.translatable(
                        "rsi.goety.warn.total_totem_souls_required",
                        String.format("%,d", totalGoetyTotemSouls),
                        String.format("%,d", availableTotemSouls)));
            }
        }

        List<MachineCandidateView> machineCandidates =
                recipeModType == null ? List.of()
                        : ModIds.GOETY.equals(recipeModType.id())
                        ? GoetyBatchDelegate.getPlanMachineCandidates(
                                player, recipe, planningEndpoint)
                        : ModIds.ID_SUMMONING_RITUALS.equals(
                                recipeModType.id())
                        ? SummoningRitualAltarBatchDelegate
                                .getPlanMachineCandidates(player, recipe)
                        : List.of();
        boolean noReadyMachine = !machineCandidates.isEmpty()
                && machineCandidates.stream().noneMatch(candidate ->
                candidate.state() == MachineCandidateView.State.READY);
        if (noReadyMachine) {
            feasible = false;
            // 临时不可用允许重试，不能误判为永久前置条件失败。
            blockingPrerequisiteFailure |=
                    !GoetyBatchDelegate.hasTemporaryMachine(machineCandidates);
            warnings.add(Component.translatable(
                    "rsi.goety.warn.no_ready_altar"));
            int warningCount = 0;
            for (MachineCandidateView candidate : machineCandidates) {
                if (warningCount++ >= MAX_GOETY_CANDIDATE_WARNINGS) break;
                warnings.add(Component.translatable(
                        "rsi.goety.warn.altar_candidate_unavailable",
                        candidate.dimension(), candidate.x(), candidate.y(),
                        candidate.z(), candidate.status()));
            }
        }
        return new Result(
                feasible, blockingPrerequisiteFailure, List.copyOf(warnings),
                machineCandidates, noReadyMachine);
    }
}
