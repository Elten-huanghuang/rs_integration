package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Pure calculations used while reserving materials for flat and graph batches.
 * Network extraction, ledger mutation, and virtual-inventory rollback remain in
 * {@link AsyncCraftChain}; this class only describes how much material is needed
 * and how already-reserved fragments are accounted for.
 */
final class MaterialReservationPlanner {
    private MaterialReservationPlanner() {}

    record FlatMaterialBatch(int executions, List<ItemStack> materials) {}

    static FlatMaterialBatch reserveFlatMaterialBatch(
            int preparedBatch, IntUnaryOperator prepare,
            IntFunction<List<ItemStack>> reserve) {
        if (preparedBatch <= 0) throw new IllegalArgumentException("prepared batch must be positive");
        int batch = preparedBatch;
        while (true) {
            List<ItemStack> materials = reserve.apply(batch);
            if (materials != null) return new FlatMaterialBatch(batch, materials);
            if (batch == 1) return new FlatMaterialBatch(0, null);
            int limit = Math.max(1, batch / 2);
            batch = prepare.applyAsInt(limit);
            if (batch <= 0 || batch > limit) {
                throw new IllegalStateException("Invalid reduced flat batch " + batch + " for limit " + limit);
            }
        }
    }

    static List<IngredientSpec> scaleGraphSpecsForExecutions(
            List<IngredientSpec> specs,
            List<IBatchDelegate.MaterialReservationScope> scopes,
            int executions) {
        int multiplier = Math.max(1, executions);
        List<IngredientSpec> scaledSpecs = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            IngredientSpec spec = specs.get(i);
            boolean reusable = i < scopes.size()
                    && scopes.get(i) == IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE;
            int count = reusable ? spec.count() : StepExecutor.mulCount(spec.count(), multiplier);
            scaledSpecs.add(new IngredientSpec(spec.ingredient(), count, spec.role()));
        }
        return List.copyOf(scaledSpecs);
    }

    static List<Integer> reusableMaterialIndices(
            List<IBatchDelegate.MaterialReservationScope> scopes, int specCount) {
        List<Integer> indices = new ArrayList<>();
        int limit = Math.min(Math.max(0, specCount), scopes.size());
        for (int i = 0; i < limit; i++) {
            if (scopes.get(i) == IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE) {
                indices.add(i);
            }
        }
        return List.copyOf(indices);
    }

    static boolean graphMaterialPoolsDrained(List<ItemStack> initialPool,
                                             List<ItemStack> producerPool) {
        return initialPool.stream().allMatch(stack -> stack == null || stack.isEmpty())
                && producerPool.stream().allMatch(stack -> stack == null || stack.isEmpty());
    }

    static List<ItemStack> consumedFragments(List<ItemStack> before, List<ItemStack> after) {
        List<ItemStack> consumed = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            ItemStack original = before.get(i);
            int remaining = i < after.size() ? after.get(i).getCount() : 0;
            int count = original.getCount() - remaining;
            if (count > 0) consumed.add(original.copyWithCount(count));
        }
        return List.copyOf(consumed);
    }
}
