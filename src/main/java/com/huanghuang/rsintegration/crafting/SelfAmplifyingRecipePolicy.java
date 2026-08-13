package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Scales terminal inputs while preserving the seed of a self-amplifying recipe. */
public final class SelfAmplifyingRecipePolicy {
    private SelfAmplifyingRecipePolicy() {}

    public static List<IngredientRef> scaleTargetInputs(RecipeNode target, int executions) {
        int multiplier = Math.max(1, executions);
        boolean amplification = isSelfAmplifying(target);
        List<IngredientRef> scaled = new ArrayList<>(target.inputs().size());
        for (IngredientRef input : target.inputs()) {
            int count = amplification && input.alternatives().contains(target.output())
                    ? input.count() : saturatingMultiply(input.count(), multiplier);
            scaled.add(new IngredientRef(input.alternatives(), count));
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
            int count = amplification && isConsumedSelfInput(spec, output)
                    ? spec.count() : CraftPacketUtils.requiredCount(spec, multiplier);
            scaled.add(new IngredientSpec(spec.ingredient(), count, spec.role()));
        }
        return List.copyOf(scaled);
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
