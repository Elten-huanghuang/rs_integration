package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.InputBufferPlanner;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.List;
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
                inputs.stream().map(InputSlot::asPlannerSpec).toList(),
                outputs.stream().map(InputBufferContract::asPlannerSpec).toList());
    }

    public static InputBufferContract none() {
        return new InputBufferContract(0, List.of(), List.of());
    }

    public record InputSlot(String entryId, int slot, ItemStack prototype,
                            int perOperation, boolean reusable, int capacity) {
        public InputSlot {
            if (entryId == null || entryId.isBlank()) {
                throw new IllegalArgumentException("input entry id must not be blank");
            }
            if (slot < 0) throw new IllegalArgumentException("input slot must be non-negative");
            if (perOperation < 0) {
                throw new IllegalArgumentException("input per-operation count must be non-negative");
            }
            if (capacity <= 0) throw new IllegalArgumentException("input capacity must be positive");
            prototype = prototype == null ? ItemStack.EMPTY : prototype.copyWithCount(1);
        }

        private InputBufferPlanner.InputSlotSpec asPlannerSpec() {
            return new InputBufferPlanner.InputSlotSpec(
                    entryId, slot, prototype, perOperation, reusable, capacity);
        }
    }

    private static void validateUniqueInputs(List<InputSlot> slots) {
        Set<String> entryIds = new HashSet<>();
        Set<Integer> physicalSlots = new HashSet<>();
        for (InputSlot slot : slots) {
            if (!entryIds.add(slot.entryId())) {
                throw new IllegalArgumentException("duplicate input entry id " + slot.entryId());
            }
            if (!physicalSlots.add(slot.slot())) {
                throw new IllegalArgumentException("duplicate input slot " + slot.slot());
            }
        }
    }

    private static InputBufferPlanner.OutputPortSpec asPlannerSpec(OutputContract.Port port) {
        return new InputBufferPlanner.OutputPortSpec(port.portId(), port.physicalPort(),
                port.prototype(), port.perOperation(), port.kind(), port.source());
    }
}
