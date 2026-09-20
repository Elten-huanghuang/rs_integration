package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import net.minecraft.world.item.ItemStack;

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

    public record InputSlotSpec(String entryId, int slot, ItemStack prototype,
                                int perOperation, boolean reusable, int capacity) {
        public InputSlotSpec {
            if (entryId == null || entryId.isBlank()) {
                throw new IllegalArgumentException("input entry id must not be blank");
            }
            if (slot < 0) throw new IllegalArgumentException("input slot must be non-negative");
            if (perOperation < 0) throw new IllegalArgumentException("per-operation count must be non-negative");
            if (capacity <= 0) throw new IllegalArgumentException("input capacity must be positive");
            prototype = prototype == null ? ItemStack.EMPTY : prototype.copyWithCount(1);
        }

        /** Compatibility constructor for delegates that only know material order. */
        public InputSlotSpec(int slot, ItemStack prototype, int perOperation,
                             boolean reusable, int capacity) {
            this("legacy:input:" + slot, slot, prototype, perOperation, reusable, capacity);
        }
    }

    public record OutputPortSpec(String portId, Integer port, ItemStack prototype,
                                 int perOperation, InputBufferPlan.OutputPort.Kind kind,
                                 OutputContract.Source source) {
        public OutputPortSpec {
            if (portId == null || portId.isBlank()) {
                throw new IllegalArgumentException("output port id must not be blank");
            }
            if (port != null && port < 0) {
                throw new IllegalArgumentException("output port must be non-negative");
            }
            if (perOperation < 0) throw new IllegalArgumentException("output count must be non-negative");
            prototype = prototype == null ? ItemStack.EMPTY : prototype.copyWithCount(1);
            kind = kind == null ? InputBufferPlan.OutputPort.Kind.PRIMARY : kind;
            source = source == null ? OutputContract.Source.SLOT : source;
        }

        /** Compatibility constructor for single-primary-output delegates. */
        public OutputPortSpec(int port, ItemStack prototype, int perOperation) {
            this("legacy:output:" + port, port, prototype, perOperation,
                    InputBufferPlan.OutputPort.Kind.PRIMARY, OutputContract.Source.SLOT);
        }

        /** Compatibility constructor for physical slot outputs without an explicit source. */
        public OutputPortSpec(String portId, int port, ItemStack prototype,
                              int perOperation, InputBufferPlan.OutputPort.Kind kind) {
            this(portId, port, prototype, perOperation, kind, OutputContract.Source.SLOT);
        }
    }

    public static InputBufferPlan plan(int requestedOperations, int configuredCapacity,
                                       List<InputSlotSpec> inputSpecs,
                                       List<OutputPortSpec> outputSpecs) {
        int requested = Math.max(0, requestedOperations);
        int capacity = Math.max(0, configuredCapacity);
        if (requested == 0 || capacity == 0 || inputSpecs == null || inputSpecs.isEmpty()) {
            return InputBufferPlan.none();
        }
        validateUniqueInputSlots(inputSpecs);
        validateUniqueOutputPorts(outputSpecs);

        int operations = Math.min(requested, capacity);
        List<InputBufferPlan.InputSlot> inputs = new ArrayList<>(inputSpecs.size());
        for (InputSlotSpec spec : inputSpecs) {
            long required = (long) spec.perOperation() * operations;
            if (spec.reusable()) required = spec.perOperation();
            if (required > spec.capacity()) {
                operations = Math.min(operations,
                        spec.reusable() ? spec.capacity() / Math.max(1, spec.perOperation())
                                : spec.capacity() / Math.max(1, spec.perOperation()));
            }
        }
        if (operations <= 0) return InputBufferPlan.none();

        for (InputSlotSpec spec : inputSpecs) {
            int count = spec.reusable()
                    ? spec.perOperation()
                    : Math.multiplyExact(spec.perOperation(), operations);
            inputs.add(new InputBufferPlan.InputSlot(spec.entryId(), spec.slot(),
                    spec.prototype().copyWithCount(count), spec.perOperation(), spec.reusable()));
        }
        List<InputBufferPlan.OutputPort> outputs = new ArrayList<>();
        if (outputSpecs != null) {
            for (OutputPortSpec spec : outputSpecs) {
                int count = Math.multiplyExact(spec.perOperation(), operations);
                outputs.add(new InputBufferPlan.OutputPort(spec.portId(), spec.port(),
                        spec.prototype().copyWithCount(count), spec.perOperation(), spec.kind(),
                        spec.source()));
            }
        }
        return new InputBufferPlan(operations, inputs, outputs);
    }

    /** Legacy-compatible conversion for delegates that expose ordered materials. */
    public static InputBufferPlan fromMaterials(int requestedOperations, int configuredCapacity,
                                                List<IngredientSpec> materials,
                                                List<IBatchDelegate.MaterialReservationScope> scopes,
                                                List<ItemStack> resolvedStacks) {
        if (materials == null || resolvedStacks == null || materials.size() != resolvedStacks.size()) {
            return InputBufferPlan.none();
        }
        List<InputSlotSpec> specs = new ArrayList<>(materials.size());
        for (int i = 0; i < materials.size(); i++) {
            IngredientSpec material = materials.get(i);
            ItemStack resolved = resolvedStacks.get(i);
            if (material == null || material.isEmpty() || resolved == null || resolved.isEmpty()) continue;
            boolean reusable = scopes != null && i < scopes.size()
                    && scopes.get(i) == IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE;
            specs.add(new InputSlotSpec(i, resolved, material.count(), reusable,
                    Math.max(1, resolved.getMaxStackSize())));
        }
        return plan(requestedOperations, configuredCapacity, specs, List.of());
    }

    private static void validateUniqueInputSlots(List<InputSlotSpec> specs) {
        Set<Integer> seen = new HashSet<>();
        Set<String> entryIds = new HashSet<>();
        for (InputSlotSpec spec : specs) {
            if (!seen.add(spec.slot())) throw new IllegalArgumentException(
                    "duplicate input slot " + spec.slot());
            if (!entryIds.add(spec.entryId())) throw new IllegalArgumentException(
                    "duplicate input entry id " + spec.entryId());
        }
    }

    private static void validateUniqueOutputPorts(List<OutputPortSpec> specs) {
        if (specs == null) return;
        Set<Integer> seen = new HashSet<>();
        Set<String> portIds = new HashSet<>();
        for (OutputPortSpec spec : specs) {
            if (spec.port() != null && !seen.add(spec.port())) throw new IllegalArgumentException(
                    "duplicate output port " + spec.port());
            if (!portIds.add(spec.portId())) throw new IllegalArgumentException(
                    "duplicate output port id " + spec.portId());
        }
    }
}
