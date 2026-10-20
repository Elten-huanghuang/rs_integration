package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * One intermediate crafting step in a plan.
 *
 * @param recipeWidth         grid columns for shaped recipes, 0 = linear layout
 * @param recipeHeight        grid rows for shaped recipes, 0 = linear layout
 * @param alternativeModTypes mod type id for each entry in {@code alternatives} (parallel list)
 */
public record PlanStep(
        ResourceLocation recipeId,
        ItemStack output,
        long batches,
        List<ItemStack> inputs,
        List<ResourceLocation> alternatives,
        @Nullable ModType modType,
        int depth,
        boolean hasOrSiblings,
        int recipeWidth,
        int recipeHeight,
        List<String> alternativeModTypes,
        List<DemandRole> inputRoles
) {
    public PlanStep {
        inputs = List.copyOf(inputs);
        alternatives = List.copyOf(alternatives);
        alternativeModTypes = List.copyOf(alternativeModTypes);
        inputRoles = inputRoles.isEmpty() && !inputs.isEmpty()
                ? Collections.nCopies(inputs.size(), DemandRole.CONSUMED)
                : List.copyOf(inputRoles);
        if (inputRoles.size() != inputs.size()) {
            throw new IllegalArgumentException("inputRoles must align with inputs");
        }
    }

    public PlanStep(ResourceLocation recipeId, ItemStack output, long batches,
                    List<ItemStack> inputs, List<ResourceLocation> alternatives,
                    @Nullable ModType modType, int depth, boolean hasOrSiblings,
                    int recipeWidth, int recipeHeight, List<String> alternativeModTypes) {
        this(recipeId, output, batches, inputs, alternatives, modType, depth, hasOrSiblings,
                recipeWidth, recipeHeight, alternativeModTypes, Collections.emptyList());
    }

    public PlanStep(ResourceLocation recipeId, ItemStack output, long batches,
                    List<ItemStack> inputs, List<ResourceLocation> alternatives,
                    @Nullable ModType modType, int depth, boolean hasOrSiblings,
                    int recipeWidth, int recipeHeight) {
        this(recipeId, output, batches, inputs, alternatives, modType, depth, hasOrSiblings,
                recipeWidth, recipeHeight, Collections.emptyList(), Collections.emptyList());
    }

    public PlanStep(ResourceLocation recipeId, ItemStack output, long batches,
                    List<ItemStack> inputs, List<ResourceLocation> alternatives,
                    @Nullable ModType modType, int depth, boolean hasOrSiblings) {
        this(recipeId, output, batches, inputs, alternatives, modType, depth, hasOrSiblings,
                0, 0, Collections.emptyList(), Collections.emptyList());
    }

    public PlanStep(ResourceLocation recipeId, ItemStack output, long batches,
                    List<ItemStack> inputs, List<ResourceLocation> alternatives,
                    @Nullable ModType modType) {
        this(recipeId, output, batches, inputs, alternatives, modType, 0, false,
                0, 0, Collections.emptyList(), Collections.emptyList());
    }

    public PlanStep(ResourceLocation recipeId, ItemStack output, long batches,
                    List<ItemStack> inputs, List<ResourceLocation> alternatives) {
        this(recipeId, output, batches, inputs, alternatives, null, 0, false,
                0, 0, Collections.emptyList(), Collections.emptyList());
    }

    public PlanStep(ResourceLocation recipeId, ItemStack output, long batches,
                    List<ItemStack> inputs) {
        this(recipeId, output, batches, inputs, Collections.emptyList(), null, 0, false,
                0, 0, Collections.emptyList(), Collections.emptyList());
    }

    public long totalInputCount() {
        long n = 0L;
        for (int i = 0; i < inputs.size(); i++) {
            n = SaturatingLongMath.add(n, totalInputCount(i, batches));
        }
        return n;
    }

    public DemandRole inputRole(int index) {
        return index >= 0 && index < inputRoles.size()
                ? inputRoles.get(index) : DemandRole.CONSUMED;
    }

    public long totalInputCount(int index, long executions) {
        if (index < 0 || index >= inputs.size()) return 0L;
        int count = Math.max(0, inputs.get(index).getCount());
        DemandRole role = inputRole(index);
        if (role == DemandRole.CATALYST
                || role == DemandRole.FUEL
                || role == DemandRole.DURABILITY) return count;
        if (isSelfAmplifying() && MaterialMatcher.equivalentRuntimeFragment(inputs.get(index), output)) {
            return count;
        }
        return SaturatingLongMath.multiply(count, Math.max(1L, executions));
    }

    private boolean isSelfAmplifying() {
        long selfConsumed = 0L;
        for (int i = 0; i < inputs.size(); i++) {
            DemandRole role = inputRole(i);
            if (role == DemandRole.CATALYST
                    || role == DemandRole.FUEL
                    || role == DemandRole.DURABILITY) continue;
            ItemStack input = inputs.get(i);
            if (MaterialMatcher.equivalentRuntimeFragment(input, output)) selfConsumed += input.getCount();
        }
        return selfConsumed > 0L && output.getCount() > selfConsumed;
    }

    public long totalOutputCount() {
        return SaturatingLongMath.multiply(output.getCount(), batches);
    }
}
