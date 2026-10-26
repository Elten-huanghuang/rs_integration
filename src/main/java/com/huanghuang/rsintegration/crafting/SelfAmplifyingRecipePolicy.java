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
