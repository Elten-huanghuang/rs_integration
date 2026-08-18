package com.huanghuang.rsintegration.crafting;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Set;

/** Tracks recipe/output pairs that are currently being expanded by the resolver. */
final class RecipeCycleGuard {
    private final Set<RecipeBranch> branches = new HashSet<>();
    private final Set<CraftingResolver.StackKey> outputs = new HashSet<>();

    boolean containsBranch(ResourceLocation recipeId, ItemStack output) {
        return branches.contains(RecipeBranch.of(recipeId, output));
    }

    boolean containsOutput(ItemStack output) {
        return outputs.contains(CraftingResolver.StackKey.of(output, output.hasTag()));
    }

    void enter(ResourceLocation recipeId, ItemStack output) {
        branches.add(RecipeBranch.of(recipeId, output));
        outputs.add(CraftingResolver.StackKey.of(output, output.hasTag()));
    }

    void leave(ResourceLocation recipeId, ItemStack output) {
        branches.remove(RecipeBranch.of(recipeId, output));
        outputs.remove(CraftingResolver.StackKey.of(output, output.hasTag()));
    }

    private record RecipeBranch(ResourceLocation recipeId,
                                CraftingResolver.StackKey output) {
        private static RecipeBranch of(ResourceLocation recipeId, ItemStack output) {
            return new RecipeBranch(recipeId,
                    CraftingResolver.StackKey.of(output, output.hasTag()));
        }
    }
}
