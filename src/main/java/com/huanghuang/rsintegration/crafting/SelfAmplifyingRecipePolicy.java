package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Scales terminal inputs while preserving the seed of a self-amplifying recipe. */
public final class SelfAmplifyingRecipePolicy {
    private SelfAmplifyingRecipePolicy() {}

    public static List<IngredientRef> scaleTargetInputs(RecipeNode target, int executions) {
        int multiplier = Math.max(1, executions);
        boolean amplification = isSelfAmplifying(target);
        List<IngredientRef> scaled = new ArrayList<>(target.inputs().size());
        for (IngredientRef input : target.inputs()) {
            List<MaterialRef> alternatives = input.alternatives();
            boolean selfInput = input.role() != DemandRole.CATALYST
                    && referencesOutput(input, target.output());
            if (input.role() != DemandRole.CATALYST
                    && !amplification && alternatives.size() > 1
                    && alternatives.contains(target.output())) {
                alternatives = alternatives.stream()
                        .filter(material -> !material.equals(target.output()))
                        .toList();
            }
            long count = input.role() == DemandRole.CATALYST
                    || amplification && selfInput
                    ? input.count() : saturatingMultiply(input.count(), multiplier);
            scaled.add(new IngredientRef(alternatives, count, input.nbtMatchMode(), input.role()));
        }
        return List.copyOf(scaled);
    }

    /**
     * 普通终端规划的是“目标配方输入”；自增终端必须规划“目标产物需求”，
     * 否则库存中的种子会被误认为已满足整个请求，产生零个生产步骤。
     */
    public static List<IngredientRef> planningRoots(RecipeNode target, int executions) {
        if (!isSelfAmplifying(target)) {
            return scaleTargetInputs(target, executions);
        }
        return List.of(new IngredientRef(
                List.of(target.output()), Math.max(1, executions),
                target.output().runtimeNbt()
                        ? ImmutableRecipeGraph.NbtMatchMode.ANY
                        : ImmutableRecipeGraph.NbtMatchMode.EXACT));
    }

    /**
     * 目标配方本身是“没有库存支撑的反向换算”时（例如 Prefab 的分解压缩萤石：
     * 1 压缩萤石 → 9 萤石，而压缩萤石又只能由 9 萤石压出来），按配方输入规划
     * 一定会绕回自己的产物，形成“9 个合成 1 个压缩、再分解成 9 个”的闭环。
     * 这种目标应改为按产物需求量规划，交给正向配方（如 4 萤石粉 → 1 萤石）。
     */
    public static boolean isUnseededReverseTarget(RecipeNode target,
                                                  ImmutableRecipeGraph graph,
                                                  Map<MaterialRef, Long> stock) {
        if (target == null || graph == null || stock == null) return false;
        // 自增配方已经有自己的 roots/守卫策略，不能走反向换算的退化逻辑。
        if (isSelfAmplifying(target)) return false;
        boolean hasForwardProducer = graph.recipesByOutput()
                .getOrDefault(target.output(), List.of())
                .stream()
                .anyMatch(producer -> !producer.recipeId().equals(target.recipeId()));
        if (!hasForwardProducer) return false;
        for (IngredientRef input : target.inputs()) {
            if (input.role() == DemandRole.CATALYST) continue;
            if (input.alternatives().isEmpty() || input.count() <= 0L) continue;
            if (stockCoverage(input, stock) >= input.count()) continue;
            boolean sawProducer = false;
            boolean reverseOnly = true;
            for (MaterialRef alternative : input.alternatives()) {
                List<RecipeNode> producers = graph.recipesByOutput()
                        .getOrDefault(alternative, List.of());
                if (producers.isEmpty()) {
                    reverseOnly = false;
                    break;
                }
                sawProducer = true;
                if (producers.stream().anyMatch(producer ->
                        producer.inputs().stream().noneMatch(ingredient ->
                                ingredient.alternatives().contains(target.output())))) {
                    reverseOnly = false;
                    break;
                }
            }
            if (sawProducer && reverseOnly) return true;
        }
        return false;
    }

    /** 反向换算目标退化后的规划根：直接按目标产物的需求量规划。 */
    public static List<IngredientRef> directProductionRoots(RecipeNode target, int executions) {
        long count = saturatingMultiply(Math.max(1L, target.outputCount()),
                Math.max(1L, executions));
        return List.of(new IngredientRef(List.of(target.output()), count,
                target.output().runtimeNbt()
                        ? ImmutableRecipeGraph.NbtMatchMode.ANY
                        : ImmutableRecipeGraph.NbtMatchMode.EXACT));
    }

    private static long stockCoverage(IngredientRef ingredient, Map<MaterialRef, Long> stock) {
        long total = 0L;
        for (MaterialRef alternative : ingredient.alternatives()) {
            for (Map.Entry<MaterialRef, Long> entry : stock.entrySet()) {
                if (entry.getValue() == null || entry.getValue() <= 0L) continue;
                if (!entry.getKey().itemId().equals(alternative.itemId())) continue;
                total = saturatingAdd(total, entry.getValue());
            }
        }
        return total;
    }

    public static List<IngredientSpec> scaleTargetInputs(List<IngredientSpec> specs,
                                                         ItemStack output,
                                                         int executions) {
        int multiplier = Math.max(1, executions);
        boolean amplification = isSelfAmplifying(specs, output);
        List<IngredientSpec> scaled = new ArrayList<>(specs.size());
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            IngredientSpec effective = amplification
                    ? spec : excludeNonProductiveSelfCandidate(spec, output);
            int count = amplification && isConsumedSelfInput(spec, output)
                    ? spec.count() : CraftPacketUtils.requiredCount(spec, multiplier);
            scaled.add(new IngredientSpec(
                    effective.ingredient(), count, spec.consumption()));
        }
        return List.copyOf(scaled);
    }

    public static List<ScaledIngredientSpec> scaleTargetInputsScaled(
            List<ScaledIngredientSpec> specs, ItemStack output, long executions) {
        long multiplier = Math.max(1L, executions);
        boolean amplification = isScaledSelfAmplifying(specs, output);
        List<ScaledIngredientSpec> scaled = new ArrayList<>(specs.size());
        for (ScaledIngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ScaledIngredientSpec effective = amplification
                    ? spec : excludeNonProductiveSelfCandidate(spec, output);
            long count = amplification && isConsumedSelfInput(spec, output)
                    ? spec.count() : saturatingMultiply(spec.count(), multiplier);
            scaled.add(new ScaledIngredientSpec(
                    effective.ingredient(), count, spec.consumption()));
        }
        return List.copyOf(scaled);
    }

    public static List<ScaledIngredientSpec> scaleTargetInputsLong(
            List<IngredientSpec> specs, ItemStack output, long executions) {
        long multiplier = Math.max(1L, executions);
        boolean amplification = isSelfAmplifying(specs, output);
        List<ScaledIngredientSpec> scaled = new ArrayList<>(specs.size());
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ScaledIngredientSpec effective = amplification
                    ? ScaledIngredientSpec.of(spec, spec.count())
                    : excludeNonProductiveSelfCandidate(
                            ScaledIngredientSpec.of(spec, spec.count()), output);
            long count = amplification && isConsumedSelfInput(spec, output)
                    ? spec.count() : CraftPacketUtils.requiredCount(spec, multiplier);
            scaled.add(new ScaledIngredientSpec(
                    effective.ingredient(), count, spec.consumption()));
        }
        return List.copyOf(scaled);
    }

    /**
     * A broad terminal ingredient may contain the recipe's own output (for
     * example the wooden-chests tag accepts a trapped chest). Consuming that
     * output from an earlier batch gives zero net production, so remove only
     * that alternative. Exact self-inputs and genuinely amplifying recipes are
     * handled separately and remain untouched.
     */
    public static IngredientSpec excludeNonProductiveSelfCandidate(
            IngredientSpec spec, ItemStack output) {
        if (spec == null || spec.isEmpty() || output == null || output.isEmpty()
                || spec.role() == DemandRole.CATALYST) return spec;
        ItemStack[] candidates = spec.ingredient().getItems();
        if (candidates.length < 2 || !spec.ingredient().test(output)) return spec;
        List<ItemStack> filtered = Stream.of(candidates)
                .filter(stack -> !MaterialMatcher.equivalentRuntimeFragment(stack, output))
                .map(stack -> stack.copyWithCount(1))
                .toList();
        if (filtered.isEmpty() || filtered.size() == candidates.length) return spec;
        return new IngredientSpec(
                Ingredient.of(filtered.stream()),
                spec.count(), spec.consumption());
    }

    public static ScaledIngredientSpec excludeNonProductiveSelfCandidate(
            ScaledIngredientSpec spec, ItemStack output) {
        if (spec == null || spec.isEmpty() || output == null || output.isEmpty()
                || spec.role() == DemandRole.CATALYST) return spec;
        ItemStack[] candidates = spec.ingredient().getItems();
        if (candidates.length < 2 || !spec.ingredient().test(output)) return spec;
        List<ItemStack> filtered = Stream.of(candidates)
                .filter(stack -> !MaterialMatcher.equivalentRuntimeFragment(stack, output))
                .map(stack -> stack.copyWithCount(1))
                .toList();
        if (filtered.isEmpty() || filtered.size() == candidates.length) return spec;
        return new ScaledIngredientSpec(
                Ingredient.of(filtered.stream()), spec.count(), spec.consumption());
    }

    public static boolean isSelfAmplifying(RecipeNode recipe) {
        long selfConsumed = selfConsumed(recipe.inputs(), recipe.output());
        return selfConsumed > 0 && recipe.outputCount() > selfConsumed;
    }

    public static boolean isSelfAmplifying(List<IngredientSpec> specs, ItemStack output) {
        if (output == null || output.isEmpty()) return false;
        long selfConsumed = 0L;
        for (IngredientSpec spec : specs) {
            if (isConsumedSelfInput(spec, output)) selfConsumed += spec.count();
        }
        return selfConsumed > 0L && output.getCount() > selfConsumed;
    }

    public static boolean isScaledSelfAmplifying(
            List<ScaledIngredientSpec> specs, ItemStack output) {
        if (output == null || output.isEmpty()) return false;
        long selfConsumed = 0L;
        for (ScaledIngredientSpec spec : specs) {
            if (isConsumedSelfInput(spec, output)) {
                selfConsumed = saturatingAdd(selfConsumed, spec.count());
            }
        }
        return selfConsumed > 0L && output.getCount() > selfConsumed;
    }

    private static boolean isConsumedSelfInput(IngredientSpec spec, ItemStack output) {
        return !spec.isEmpty() && spec.role() != DemandRole.CATALYST
                && CraftingResolver.consumesOutput(spec.ingredient(), output);
    }

    private static boolean isConsumedSelfInput(
            ScaledIngredientSpec spec, ItemStack output) {
        return !spec.isEmpty() && spec.role() != DemandRole.CATALYST
                && CraftingResolver.consumesOutput(spec.ingredient(), output);
    }

    private static long selfConsumed(List<IngredientRef> inputs, MaterialRef output) {
        long consumed = 0L;
        for (IngredientRef input : inputs) {
            if (input.role() != DemandRole.CATALYST
                    && referencesOutput(input, output)) {
                consumed = Long.MAX_VALUE - consumed < input.count()
                        ? Long.MAX_VALUE : consumed + input.count();
            }
        }
        return consumed;
    }

    private static boolean referencesOutput(IngredientRef input, MaterialRef output) {
        if (input.alternatives().isEmpty()) return false;
        for (MaterialRef alternative : input.alternatives()) {
            if (alternative.kind() != output.kind()
                    || !alternative.resourceId().equals(output.resourceId())) return false;
            if (output.runtimeNbt() || alternative.runtimeNbt()
                    || alternative.equals(output)) continue;
            return false;
        }
        return true;
    }

    public static boolean isSelfInput(IngredientRef input, MaterialRef output) {
        return input != null && output != null && input.role() != DemandRole.CATALYST
                && referencesOutput(input, output);
    }

    private static long saturatingMultiply(long count, long multiplier) {
        if (count <= 0L || multiplier <= 0) return 0L;
        return count > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE : count * multiplier;
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L) return left;
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }
}
