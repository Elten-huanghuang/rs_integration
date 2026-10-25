package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable physical layout for one input-buffer dispatch. */
public record InputBufferPlan(
        int operations,
        List<InputSlot> inputs,
        List<OutputContract.Port> outputs) {

    public InputBufferPlan {
        operations = Math.max(0, operations);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = new OutputContract(outputs).ports();
    }

    public boolean enabled() {
        return operations > 0 && !inputs.isEmpty();
    }

    public static InputBufferPlan none() {
        return new InputBufferPlan(0, List.of(), List.of());
    }

    /**
     * Binds a planned physical layout to the exact stacks reserved by the
     * chain. The caller must keep the stable input-entry ordering intact.
     */
    public InputBufferPlan withResolvedInputs(List<ItemStack> resolved) {
        if (resolved == null || resolved.size() != inputs.size()) return none();
        List<InputSlot> bound = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            InputSlot slot = inputs.get(i);
            ItemStack stack = resolved.get(i);
            if (stack == null || stack.isEmpty()) return none();
            if (stack.getCount() != slot.stack().getCount()) return none();
            bound.add(new InputSlot(slot.entryId(), slot.slot(), stack,
                    slot.perOperation(), slot.consumption()));
        }
        return new InputBufferPlan(operations, bound, outputs);
    }

    /** Bind planned slots by stable material entry id rather than list position. */
    public InputBufferPlan withResolvedMaterials(
            List<OperationStartContext.ReservedMaterial> resolved) {
        if (resolved == null || resolved.isEmpty()) return none();
        Map<String, ItemStack> byId = new HashMap<>();
        for (OperationStartContext.ReservedMaterial material : resolved) {
            if (byId.put(material.entryId(), material.stack()) != null) return none();
        }
        List<InputSlot> bound = new ArrayList<>(inputs.size());
        for (InputSlot slot : inputs) {
            ItemStack stack = byId.get(slot.entryId());
            if (stack == null || stack.isEmpty()
                    || stack.getCount() != slot.stack().getCount()) return none();
            bound.add(new InputSlot(slot.entryId(), slot.slot(), stack,
                    slot.perOperation(), slot.consumption()));
        }
        return new InputBufferPlan(operations, bound, outputs);
    }

    /** One physical input slot. The stack count is the amount placed before start. */
    public record InputSlot(String entryId, int slot, ItemStack stack,
                            int perOperation, InputConsumption consumption) {
        public InputSlot {
            if (entryId == null || entryId.isBlank()) {
                throw new IllegalArgumentException("input entry id must not be blank");
            }
            if (slot < 0) throw new IllegalArgumentException("input slot must be non-negative");
            if (perOperation < 0) {
                throw new IllegalArgumentException("input per-operation count must be non-negative");
            }
            if (consumption == null) {
                throw new IllegalArgumentException("input consumption is required");
            }
            stack = stack == null ? ItemStack.EMPTY : stack.copy();
        }
    }

}
