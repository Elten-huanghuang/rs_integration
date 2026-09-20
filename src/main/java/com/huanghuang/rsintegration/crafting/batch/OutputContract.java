package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stable declaration of every physical or virtual output channel of one operation. */
public record OutputContract(List<Port> ports) {
    public enum Source {
        SLOT,
        WORLD,
        VIRTUAL
    }

    public OutputContract {
        ports = ports == null ? List.of() : List.copyOf(ports);
        Set<String> ids = new HashSet<>();
        Set<Integer> physicalPorts = new HashSet<>();
        for (Port port : ports) {
            if (!ids.add(port.portId())) {
                throw new IllegalArgumentException("duplicate output port id " + port.portId());
            }
            if (port.physicalPort() != null && !physicalPorts.add(port.physicalPort())) {
                throw new IllegalArgumentException("duplicate physical output port "
                        + port.physicalPort());
            }
        }
    }

    public static OutputContract none() {
        return new OutputContract(List.of());
    }

    /** Builds the runtime accounting declaration for one already-planned buffered dispatch. */
    public static OutputContract fromPlan(InputBufferPlan plan) {
        if (plan == null || plan.outputs().isEmpty()) return none();
        return new OutputContract(plan.outputs().stream()
                .map(output -> new Port(output.portId(), output.port(), output.expected(),
                        output.perOperation(), output.kind(), output.source()))
                .toList());
    }

    public record Port(String portId, Integer physicalPort, ItemStack prototype,
                       int perOperation, InputBufferPlan.OutputPort.Kind kind, Source source) {
        public Port {
            if (portId == null || portId.isBlank()) {
                throw new IllegalArgumentException("output port id must not be blank");
            }
            if (physicalPort != null && physicalPort < 0) {
                throw new IllegalArgumentException("physical output port must be non-negative");
            }
            if (perOperation < 0) {
                throw new IllegalArgumentException("output per-operation count must be non-negative");
            }
            prototype = prototype == null ? ItemStack.EMPTY : prototype.copyWithCount(1);
            if (prototype.isEmpty() && perOperation > 0) {
                throw new IllegalArgumentException("output prototype is required when count is positive");
            }
            kind = kind == null ? InputBufferPlan.OutputPort.Kind.PRIMARY : kind;
            source = source == null ? Source.SLOT : source;
        }

        public int expectedCount(int operations) {
            return Math.multiplyExact(perOperation, Math.max(0, operations));
        }
    }
}
