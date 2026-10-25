package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure planner for machines that accept several queued inputs while consuming
 * one recipe operation at a time. It understands explicit input slot and output
 * port IDs, so multi-slot machines do not depend on list order alone.
 */
public final class InputBufferPlanner {
    private InputBufferPlanner() {}

    public static InputBufferPlan plan(int requestedOperations, int configuredCapacity,
                                       List<InputBufferContract.InputSlot> inputSpecs,
                                       List<OutputContract.Port> outputSpecs) {
        int requested = Math.max(0, requestedOperations);
        int capacity = Math.max(0, configuredCapacity);
        if (requested == 0 || capacity == 0 || inputSpecs == null || inputSpecs.isEmpty()) {
            return InputBufferPlan.none();
        }
        validateUniqueInputSlots(inputSpecs);

        int operations = Math.min(requested, capacity);
        List<InputBufferPlan.InputSlot> inputs = new ArrayList<>(inputSpecs.size());
        for (InputBufferContract.InputSlot spec : inputSpecs) {
            boolean reusable = spec.consumption().reusableWithinWorkerBatch();
            if (reusable) {
                if (spec.perOperation() > spec.capacity()) return InputBufferPlan.none();
                continue;
            }
            long required = (long) spec.perOperation() * operations;
            if (required > spec.capacity()) {
                operations = Math.min(operations,
                        spec.capacity() / Math.max(1, spec.perOperation()));
            }
        }
        if (operations <= 0) return InputBufferPlan.none();

        for (InputBufferContract.InputSlot spec : inputSpecs) {
            int count = spec.consumption().reusableWithinWorkerBatch()
                    ? spec.perOperation()
                    : Math.multiplyExact(spec.perOperation(), operations);
            inputs.add(new InputBufferPlan.InputSlot(spec.entryId(), spec.slot(),
                    spec.prototype().copyWithCount(count), spec.perOperation(),
                    spec.consumption()));
        }
        return new InputBufferPlan(operations, inputs, outputSpecs);
    }

    private static void validateUniqueInputSlots(List<InputBufferContract.InputSlot> specs) {
        Set<Integer> seen = new HashSet<>();
        Set<String> entryIds = new HashSet<>();
        for (InputBufferContract.InputSlot spec : specs) {
            if (!seen.add(spec.slot())) throw new IllegalArgumentException(
                    "duplicate input slot " + spec.slot());
            if (!entryIds.add(spec.entryId())) throw new IllegalArgumentException(
                    "duplicate input entry id " + spec.entryId());
        }
    }

}
