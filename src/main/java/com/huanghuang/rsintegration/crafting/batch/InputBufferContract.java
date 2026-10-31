package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.InputBufferPlanner;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stable multi-slot input and multi-port output capability declaration.
 *
 * <p>This describes a machine's physical layout, not an individual start. The
 * planner derives a concrete {@link InputBufferPlan} for each requested batch.
 * Legacy delegates return {@link #none()} and retain their existing start path.</p>
 */
public record InputBufferContract(
        int operationLimit,
        List<InputSlot> inputs,
        List<OutputContract.Port> outputs) {

    public InputBufferContract {
        operationLimit = Math.max(0, operationLimit);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = new OutputContract(outputs).ports();
        validateUniqueInputs(inputs);
    }

    public boolean enabled() {
        return operationLimit > 0 && !inputs.isEmpty();
    }

    public InputBufferPlan plan(int requestedOperations) {
        if (!enabled()) return InputBufferPlan.none();
        return InputBufferPlanner.plan(requestedOperations, operationLimit,
                inputs, outputs);
    }

    public static InputBufferContract none() {
        return new InputBufferContract(0, List.of(), List.of());
    }

    /** entryId 标识逻辑物料；相同消耗条目可由多个独立物理槽共同承载。 */
    public record InputSlot(String entryId, int slot, ItemStack prototype,
                            int perOperation, int capacity,
                            InputConsumption consumption) {
        public InputSlot {
            if (entryId == null || entryId.isBlank()) {
                throw new IllegalArgumentException("input entry id must not be blank");
            }
            if (slot < 0) throw new IllegalArgumentException("input slot must be non-negative");
            if (perOperation < 0) {
                throw new IllegalArgumentException("input per-operation count must be non-negative");
            }
            if (capacity <= 0) throw new IllegalArgumentException("input capacity must be positive");
            if (consumption == null) {
                throw new IllegalArgumentException("input consumption is required");
            }
            prototype = prototype == null ? ItemStack.EMPTY : prototype.copyWithCount(1);
        }

        /** 同一消耗物料可分摊到多个独立加工槽，不重复计算配方需求。 */
        public boolean sharesMaterialWith(InputSlot other) {
            return entryId.equals(other.entryId())
                    && consumption.kind() == InputConsumption.Kind.CONSUMED
                    && consumption.equals(other.consumption())
                    && perOperation > 0 && perOperation == other.perOperation()
                    && !prototype.isEmpty()
                    && ItemStack.isSameItemSameTags(prototype, other.prototype());
        }
    }

    private static void validateUniqueInputs(List<InputSlot> slots) {
        Map<String, InputSlot> entryIds = new HashMap<>();
        Set<Integer> physicalSlots = new HashSet<>();
        for (InputSlot slot : slots) {
            InputSlot previous = entryIds.putIfAbsent(slot.entryId(), slot);
            if (previous != null && !previous.sharesMaterialWith(slot)) {
                throw new IllegalArgumentException("duplicate input entry id " + slot.entryId());
            }
            if (!physicalSlots.add(slot.slot())) {
                throw new IllegalArgumentException("duplicate input slot " + slot.slot());
            }
        }
    }
}
