package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable physical layout for one input-buffer dispatch. */
public record InputBufferPlan(
        int operations,
        List<InputSlot> inputs,
        List<OutputPort> outputs) {

    public InputBufferPlan {
        operations = Math.max(0, operations);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
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
                    slot.perOperation(), slot.reusable()));
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
                    slot.perOperation(), slot.reusable()));
        }
        return new InputBufferPlan(operations, bound, outputs);
    }

    /** One physical input slot. The stack count is the amount placed before start. */
    public record InputSlot(String entryId, int slot, ItemStack stack,
                            int perOperation, boolean reusable) {
        public InputSlot {
            if (entryId == null || entryId.isBlank()) {
                throw new IllegalArgumentException("input entry id must not be blank");
            }
            if (slot < 0) throw new IllegalArgumentException("input slot must be non-negative");
            if (perOperation < 0) {
                throw new IllegalArgumentException("input per-operation count must be non-negative");
            }
            stack = stack == null ? ItemStack.EMPTY : stack.copy();
        }

        /** Compatibility constructor for ordered legacy material lists. */
        public InputSlot(int slot, ItemStack stack, boolean reusable) {
            this("legacy:input:" + slot, slot, stack, 0, reusable);
        }
    }

    /** One declared output channel expected from this dispatch. */
    public record OutputPort(String portId, Integer port, ItemStack expected,
                             int perOperation, Kind kind, OutputContract.Source source) {
        public enum Kind {
            PRIMARY,
            SECONDARY
        }

        public OutputPort {
            if (portId == null || portId.isBlank()) {
                throw new IllegalArgumentException("output port id must not be blank");
            }
            if (port != null && port < 0) {
                throw new IllegalArgumentException("output port must be non-negative");
            }
            if (perOperation < 0) {
                throw new IllegalArgumentException("output per-operation count must be non-negative");
            }
            kind = kind == null ? Kind.PRIMARY : kind;
            source = source == null ? OutputContract.Source.SLOT : source;
            expected = expected == null ? ItemStack.EMPTY : expected.copy();
        }

        /** Compatibility constructor for output declarations without port metadata. */
        public OutputPort(int port, ItemStack expected) {
            this("legacy:output:" + port, port, expected, 0, Kind.PRIMARY,
                    OutputContract.Source.SLOT);
        }

        /** Compatibility constructor for callers that do not distinguish output source yet. */
        public OutputPort(String portId, int port, ItemStack expected,
                          int perOperation, Kind kind) {
            this(portId, port, expected, perOperation, kind, OutputContract.Source.SLOT);
        }
    }
}
