package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.world.item.ItemStack;

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
            if (!amplification && alternatives.size() > 1
                    && alternatives.contains(target.output())) {
                alternatives = alternatives.stream()
                        .filter(material -> !material.equals(target.output()))
                        .toList();
            }
            int count = amplification && input.alternatives().contains(target.output())
                    ? input.count() : saturatingMultiply(input.count(), multiplier);
            scaled.add(new IngredientRef(alternatives, count, input.nbtMatchMode()));
        }
        return List.copyOf(scaled);
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
            scaled.add(new IngredientSpec(effective.ingredient(), count, spec.role()));
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
                .filter(stack -> !ItemStack.isSameItemSameTags(stack, output))
                .map(stack -> stack.copyWithCount(1))
                .toList();
        if (filtered.isEmpty() || filtered.size() == candidates.length) return spec;
        return new IngredientSpec(
                net.minecraft.world.item.crafting.Ingredient.of(filtered.stream()),
                spec.count(), spec.role());
    }

    public static boolean isSelfAmplifying(RecipeNode recipe) {
        int selfConsumed = selfConsumed(recipe.inputs(), recipe.output());
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

    private static boolean isConsumedSelfInput(IngredientSpec spec, ItemStack output) {
        return !spec.isEmpty() && spec.role() != DemandRole.CATALYST
                && spec.ingredient().test(output);
    }

    private static int selfConsumed(List<IngredientRef> inputs, MaterialRef output) {
        long consumed = 0L;
        for (IngredientRef input : inputs) {
            if (input.alternatives().contains(output)) consumed += input.count();
        }
        return consumed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) consumed;
    }

    private static int saturatingMultiply(int count, int multiplier) {
        long scaled = (long) count * multiplier;
        return scaled > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    }
}
