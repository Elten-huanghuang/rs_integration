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

    public static boolean permitsEmptyResult(OutputContract contract) {
        return contract != null && contract.permitsEmptyResult();
    }

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

    public record PortSettlement(OutputContract.Port port, long expected, long actual,
                                 boolean matchingOutput) {
        public boolean complete() {
            return matchingOutput
                    && (!port.requiresExactProduction() || actual >= expected);
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

    /**
     * 捕获到首份产物不代表批次完成；整批数量由生产声明提供，
     * 旧适配器则沿用世界产物栈的数量。实际 NBT 留给最终结算验证。
     */
    public static boolean hasCapturedExpectedProduction(
            IBatchDelegate delegate, List<ItemStack> captured) {
        IBatchDelegate.ExpectedProduction production = delegate.getExpectedProduction();
        ItemStack expected = production == null ? delegate.getExpectedOutput() : production.item();
        int required = production == null
                ? (expected == null ? 0 : expected.getCount()) : production.count();
        if (expected == null || expected.isEmpty() || required <= 0) return false;
        long count = 0;
        for (ItemStack stack : captured) {
            if (stack != null && !stack.isEmpty() && ItemStack.isSameItem(stack, expected)) {
                count += stack.getCount();
                if (count >= required) return true;
            }
        }
        return false;
    }

    public static List<OutputAccounting.CollectedOutput> capturedWorldOutputRecords(
            OutputContract contract, List<ItemStack> captured) {
        if (captured == null || captured.isEmpty()) return List.of();
        OutputContract safeContract = contract == null ? OutputContract.none() : contract;
        List<OutputAccounting.CollectedOutput> outputs = new ArrayList<>();
        int unknownIndex = 0;
        for (ItemStack stack : captured) {
            if (stack == null || stack.isEmpty()) continue;
            List<OutputContract.Port> matches = safeContract.ports().stream()
                    .filter(port -> port.source() == OutputContract.Source.WORLD)
                    .filter(port -> MaterialMatcher.matchesOutputDeclaration(
                            MaterialKey.of(port.prototype()), stack))
                    .toList();
            String portId = matches.size() == 1
                    ? matches.get(0).portId() : "unmatched:world:" + unknownIndex++;
            outputs.add(new OutputAccounting.CollectedOutput(
                    portId, OutputContract.Source.WORLD, stack));
        }
        return List.copyOf(outputs);
    }

    public static Settlement assess(OutputContract contract, long operations,
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
            long actualCount = 0L;
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
                    actualCount = Math.addExact(actualCount, (long) output.stack().getCount());
                }
            }
            settlements.add(new PortSettlement(port, port.guaranteedCount(operations), actualCount, matching));
        }

        byPort.values().forEach(unexpected::addAll);
        return new Settlement(settlements, unexpected);
    }

    /** 旧串行入口只在最终结算时取走私有收货；按同一合同分配，不重新生成实物。 */
    public static List<CollectedOutput> attributeReceived(OutputContract contract, long operations,
                                                         List<ItemStack> received) {
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack stack : received) if (!stack.isEmpty()) remaining.add(stack.copy());
        List<CollectedOutput> result = new ArrayList<>();
        Map<String, Long> assigned = new LinkedHashMap<>();
        for (int pass = 0; pass < 2; pass++) {
            for (OutputContract.Port port : contract.ports()) {
                long limit = pass == 0 ? port.guaranteedCount(operations) : port.maximumCount(operations);
                long needed = limit - assigned.getOrDefault(port.portId(), 0L);
                for (ItemStack stack : remaining) {
                    if (needed <= 0) break;
                    if (stack.isEmpty() || !MaterialMatcher.matchesOutputDeclaration(
                            MaterialKey.of(port.prototype()), stack)) continue;
                    int take = (int) Math.min(needed, stack.getCount());
                    result.add(new CollectedOutput(port.portId(), port.source(), stack.copyWithCount(take)));
                    stack.shrink(take);
                    assigned.merge(port.portId(), (long) take, Math::addExact);
                    needed -= take;
                }
            }
        }
        // 这里只核对合同份额。余量仍由调用方的原始实物列表持有并交付，不能判为短缺。
        return List.copyOf(result);
    }
}
