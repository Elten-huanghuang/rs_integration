package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 配方输入倍率、角色对齐和有界库存消耗，不读取玩家或世界状态。 */
public final class CraftIngredientPlanMath {
    private CraftIngredientPlanMath() {}

    static List<IngredientSpec> extractPlanIngredientSpecs(CraftingRecipe recipe) {
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        return specs == null || specs.isEmpty()
                ? CraftPacketUtils.extractCraftingIngredientSpecs(recipe)
                : specs;
    }

    static List<ScaledIngredientSpec> scaleIngredientSpecs(
            List<IngredientSpec> specs, long executions) {
        List<ScaledIngredientSpec> scaled = new ArrayList<>(specs.size());
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            scaled.add(ScaledIngredientSpec.of(spec,
                    CraftPacketUtils.requiredCount(spec, executions)));
        }
        return List.copyOf(scaled);
    }

    static List<ScaledIngredientSpec> scaleIngredientSpecs(
            Recipe<?> recipe, List<IngredientSpec> specs, long executions) {
        ModRecipeHandler handler = ModRecipeHandlers.claimFor(recipe).winner();
        if (handler == null) return scaleIngredientSpecs(specs, executions);
        List<ScaledIngredientSpec> scaled = new ArrayList<>(specs.size());
        int inputIndex = 0;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            scaled.add(ScaledIngredientSpec.of(spec,
                    handler.requiredIngredientCountLong(
                            recipe, spec, inputIndex++, executions)));
        }
        return List.copyOf(scaled);
    }

    static List<ScaledIngredientSpec> scaleTerminalIngredientSpecs(
            List<IngredientSpec> specs, ItemStack output, long executions) {
        return SelfAmplifyingRecipePolicy.scaleTargetInputsLong(
                specs, output, executions);
    }

    static List<ScaledIngredientSpec> scaleTerminalIngredientSpecs(
            Recipe<?> recipe, List<IngredientSpec> specs, ItemStack output, long executions) {
        List<ScaledIngredientSpec> scaled =
                scaleTerminalIngredientSpecs(specs, output, executions);
        ModRecipeHandler handler = ModRecipeHandlers.claimFor(recipe).winner();
        if (handler == null) return scaled;

        List<ScaledIngredientSpec> adjusted = new ArrayList<>(scaled.size());
        int scaledIndex = 0;
        int inputIndex = 0;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ScaledIngredientSpec terminalSpec = scaled.get(scaledIndex++);
            long ordinaryCount = CraftPacketUtils.requiredCount(spec, executions);
            long handlerCount = handler.requiredIngredientCountLong(
                    recipe, spec, inputIndex++, executions);
            adjusted.add(handlerCount == ordinaryCount
                    ? terminalSpec
                    : new ScaledIngredientSpec(terminalSpec.ingredient(), handlerCount,
                            terminalSpec.consumption()));
        }
        return List.copyOf(adjusted);
    }

    static boolean useDirectTerminalPlan(DirectMaterialAllocator.Result allocation,
                                         List<ScaledIngredientSpec> scaledSpecs,
                                         ItemStack output) {
        return allocation.feasible()
                && !SelfAmplifyingRecipePolicy.isScaledSelfAmplifying(
                        scaledSpecs, output);
    }

    static List<DemandRole> nonEmptyInputRoles(List<IngredientSpec> specs) {
        return specs.stream()
                .filter(spec -> !spec.isEmpty())
                .map(IngredientSpec::role)
                .toList();
    }

    static List<DemandRole> nonEmptyScaledInputRoles(
            List<ScaledIngredientSpec> specs) {
        return specs.stream()
                .filter(spec -> !spec.isEmpty())
                .map(ScaledIngredientSpec::role)
                .toList();
    }

    static List<DemandRole> alignInputRoles(
            List<Ingredient> displayed, List<IngredientSpec> specs) {
        if (displayed.size() == specs.size()) {
            return specs.stream().map(spec -> spec.isEmpty()
                    ? DemandRole.CONSUMED : spec.role()).toList();
        }

        List<DemandRole> nonEmpty = nonEmptyInputRoles(specs);
        List<DemandRole> aligned = new ArrayList<>(displayed.size());
        int roleIndex = 0;
        for (Ingredient ingredient : displayed) {
            if (ingredient.isEmpty()) {
                aligned.add(DemandRole.CONSUMED);
            } else {
                aligned.add(roleIndex < nonEmpty.size()
                        ? nonEmpty.get(roleIndex++) : DemandRole.CONSUMED);
            }
        }
        return List.copyOf(aligned);
    }

    static Map<Item, Long> consumeIngredientCount(Ingredient ingredient, long amount,
                                                  Map<Item, ? extends Number> supplied) {
        Map<Item, Long> available = new LinkedHashMap<>();
        supplied.forEach((item, count) -> {
            if (item != null && count != null) {
                available.put(item, Math.max(0L, count.longValue()));
            }
        });
        return consumeIngredientCountInPlace(ingredient, amount, available,
                Collections.emptySet());
    }

    static Map<Item, Long> consumeIngredientCountInPlace(
            Ingredient ingredient, long amount, Map<Item, Long> available) {
        return consumeIngredientCountInPlace(
                ingredient, amount, available, Collections.emptySet());
    }

    static Map<Item, Long> consumeIngredientCountInPlace(
            Ingredient ingredient, long amount, Map<Item, Long> available,
            Set<Item> preferred) {
        if (ingredient == null || ingredient.isEmpty() || amount <= 0) return Map.of();

        List<Item> candidates = new ArrayList<>();
        Set<Item> seen = new HashSet<>();
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty() && seen.add(stack.getItem())) candidates.add(stack.getItem());
        }
        candidates.sort((left, right) -> Long.compare(
                available.getOrDefault(right, 0L), available.getOrDefault(left, 0L)));

        Map<Item, Long> consumed = new LinkedHashMap<>();
        long remaining = amount;
        for (Item item : candidates) {
            long stocked = Math.max(0L, available.getOrDefault(item, 0L));
            if (stocked <= 0) continue;
            long take = Math.min(remaining, stocked);
            available.put(item, stocked - take);
            consumed.put(item, take);
            remaining -= take;
            if (remaining == 0) break;
        }
        return consumed;
    }
}
