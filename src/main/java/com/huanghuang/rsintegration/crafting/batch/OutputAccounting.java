package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure per-port production accounting for slot, world, and virtual outputs. */
public final class OutputAccounting {
    private OutputAccounting() {}

    public record CollectedOutput(String portId, OutputContract.Source source, ItemStack stack) {
        public CollectedOutput {
            if (portId == null || portId.isBlank()) {
                throw new IllegalArgumentException("collected output port id must not be blank");
            }
            source = source == null ? OutputContract.Source.SLOT : source;
            stack = stack == null ? ItemStack.EMPTY : stack.copy();
            if (stack.isEmpty()) throw new IllegalArgumentException("collected output stack must not be empty");
        }
    }

    public record PortSettlement(OutputContract.Port port, int expected, int actual,
                                 boolean matchingOutput) {
        public boolean complete() {
            return matchingOutput && actual >= expected;
        }
    }

    public record Settlement(List<PortSettlement> ports, List<CollectedOutput> unexpected) {
        public Settlement {
            ports = List.copyOf(ports);
            unexpected = List.copyOf(unexpected);
        }

        public boolean complete() {
            return unexpected.isEmpty() && ports.stream().allMatch(PortSettlement::complete);
        }
    }

    public static Settlement assess(OutputContract contract, int operations,
                                    List<CollectedOutput> actualOutputs) {
        OutputContract safeContract = contract == null ? OutputContract.none() : contract;
        List<CollectedOutput> actual = actualOutputs == null ? List.of() : actualOutputs;
        Map<String, List<CollectedOutput>> byPort = new LinkedHashMap<>();
        for (CollectedOutput output : actual) {
            if (output != null) byPort.computeIfAbsent(output.portId(), ignored -> new ArrayList<>()).add(output);
        }

        List<PortSettlement> settlements = new ArrayList<>(safeContract.ports().size());
        List<CollectedOutput> unexpected = new ArrayList<>();
        for (OutputContract.Port port : safeContract.ports()) {
            List<CollectedOutput> collected = byPort.remove(port.portId());
            int actualCount = 0;
            boolean matching = true;
            if (collected != null) {
                for (CollectedOutput output : collected) {
                    if (output.source() != port.source()
                            || !MaterialMatcher.matchesOutputDeclaration(
                            MaterialKey.of(port.prototype()), output.stack())) {
                        matching = false;
                        unexpected.add(output);
                        continue;
                    }
                    actualCount = Math.addExact(actualCount, output.stack().getCount());
                }
            }
            settlements.add(new PortSettlement(port, port.expectedCount(operations), actualCount, matching));
        }

        byPort.values().forEach(unexpected::addAll);
        return new Settlement(settlements, unexpected);
    }
}
