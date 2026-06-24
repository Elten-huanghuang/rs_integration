package com.huanghuang.rsintegration.plan;

import com.huanghuang.rsintegration.batch.ModType;
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
        int batches,
        List<ItemStack> inputs,
        List<ResourceLocation> alternatives,
        @Nullable ModType modType,
        int depth,
        boolean hasOrSiblings,
        int recipeWidth,
        int recipeHeight,
        List<String> alternativeModTypes
) {
    public PlanStep {
        inputs = List.copyOf(inputs);
        alternatives = List.copyOf(alternatives);
        alternativeModTypes = List.copyOf(alternativeModTypes);
    }

    public PlanStep(ResourceLocation recipeId, ItemStack output, int batches,
                    List<ItemStack> inputs, List<ResourceLocation> alternatives,
                    @Nullable ModType modType, int depth, boolean hasOrSiblings,
                    int recipeWidth, int recipeHeight) {
        this(recipeId, output, batches, inputs, alternatives, modType, depth, hasOrSiblings,
                recipeWidth, recipeHeight, Collections.emptyList());
    }

    public PlanStep(ResourceLocation recipeId, ItemStack output, int batches,
                    List<ItemStack> inputs, List<ResourceLocation> alternatives,
                    @Nullable ModType modType, int depth, boolean hasOrSiblings) {
        this(recipeId, output, batches, inputs, alternatives, modType, depth, hasOrSiblings,
                0, 0, Collections.emptyList());
    }

    public PlanStep(ResourceLocation recipeId, ItemStack output, int batches,
                    List<ItemStack> inputs, List<ResourceLocation> alternatives,
                    @Nullable ModType modType) {
        this(recipeId, output, batches, inputs, alternatives, modType, 0, false,
                0, 0, Collections.emptyList());
    }

    public PlanStep(ResourceLocation recipeId, ItemStack output, int batches,
                    List<ItemStack> inputs, List<ResourceLocation> alternatives) {
        this(recipeId, output, batches, inputs, alternatives, null, 0, false,
                0, 0, Collections.emptyList());
    }

    public PlanStep(ResourceLocation recipeId, ItemStack output, int batches,
                    List<ItemStack> inputs) {
        this(recipeId, output, batches, inputs, Collections.emptyList(), null, 0, false,
                0, 0, Collections.emptyList());
    }

    public int totalInputCount() {
        int n = 0;
        for (ItemStack s : inputs) n += s.getCount();
        return n * batches;
    }

    public int totalOutputCount() {
        return output.getCount() * batches;
    }
}
