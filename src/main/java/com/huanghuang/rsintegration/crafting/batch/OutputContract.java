package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.recipe.OutputSemantics;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
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

    public enum Kind {
        PRIMARY,
        SECONDARY
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

    /** 完整声明的所有端口均允许缺席时，已完成的操作可以没有任何实物产出。 */
    public boolean permitsEmptyResult() {
        return !ports.isEmpty() && ports.stream().allMatch(Port::permitsEmptyResult);
    }

    /** Builds the runtime accounting declaration for one already-planned buffered dispatch. */
    public static OutputContract fromPlan(InputBufferPlan plan) {
        if (plan == null || plan.outputs().isEmpty()) return none();
        return new OutputContract(plan.outputs());
    }

    public record Port(
            String portId,
            Integer physicalPort,
            ItemStack prototype,
            int perOperation,
            Kind kind,
            Source source,
            int minimumPerOperation,
            int maximumPerOperation,
            OutputProbability probability,
            boolean dynamicIdentity,
            @Nullable ResourceLocation sourceInputEntryId
    ) implements OutputSemantics {
        @Override
        public IntegrationResourceKey resource() {
            return IntegrationResourceKey.item(prototype);
        }

        @Override
        public long guaranteedMinimum() {
            return probability.isGuaranteed() ? minimumPerOperation : 0L;
        }

        @Override
        public long maximumAmount() {
            return maximumPerOperation;
        }

        @Override
        public boolean isPrimary() {
            return kind == Kind.PRIMARY;
        }

        @Override
        public boolean targetProducing() {
            return !supportsDeterministicProjection();
        }

        public Port(String portId, Integer physicalPort, ItemStack prototype,
                    int perOperation, Kind kind, Source source) {
            this(portId, physicalPort, prototype, perOperation, kind, source,
                    perOperation, perOperation, OutputProbability.guaranteed(),
                    false, null);
        }

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
            if (minimumPerOperation < 0 || maximumPerOperation < minimumPerOperation
                    || maximumPerOperation == 0
                    && (minimumPerOperation > 0 || perOperation > 0)) {
                throw new IllegalArgumentException("invalid output per-operation range");
            }
            if (perOperation < minimumPerOperation || perOperation > maximumPerOperation) {
                throw new IllegalArgumentException(
                        "guaranteed output count must be in its per-operation range");
            }
            prototype = prototype == null ? ItemStack.EMPTY : prototype.copyWithCount(1);
            if (prototype.isEmpty() && perOperation > 0) {
                throw new IllegalArgumentException("output prototype is required when count is positive");
            }
            kind = kind == null ? Kind.PRIMARY : kind;
            source = source == null ? Source.SLOT : source;
            if (probability == null) {
                throw new IllegalArgumentException("output probability is required");
            }
        }

        public long expectedCount(long operations) {
            return Math.multiplyExact((long) perOperation, Math.max(0L, operations));
        }

        public long minimumCount(long operations) {
            return Math.multiplyExact((long) minimumPerOperation, Math.max(0L, operations));
        }

        public long guaranteedCount(long operations) {
            return Math.multiplyExact(guaranteedMinimum(), Math.max(0L, operations));
        }

        public long maximumCount(long operations) {
            return Math.multiplyExact((long) maximumPerOperation, Math.max(0L, operations));
        }

        public boolean requiresExactProduction() {
            return guaranteedMinimum() > 0L;
        }
    }
}
