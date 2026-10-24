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

    /**
     * 合并世界捕获物与委托收集结果。部分委托在世界实体已被捕获后，
     * 仍会通过配方结果兜底返回同一份产物；这里仅扣除已捕获覆盖的数量，
     * 真实额外产物、不同物品和副产物均保留。
     */
    public static List<ItemStack> mergeLegacyOutputs(
            List<ItemStack> capturedWorldOutputs,
            List<ItemStack> collectedResults,
            ItemStack expectedWorldOutput) {
        List<ItemStack> merged = new ArrayList<>();
        int coveredCount = 0;
        if (capturedWorldOutputs != null) {
            for (ItemStack captured : capturedWorldOutputs) {
                if (captured == null || captured.isEmpty()) continue;
                merged.add(captured.copy());
                if (expectedWorldOutput != null && !expectedWorldOutput.isEmpty()
                        && ItemStack.isSameItem(expectedWorldOutput, captured)) {
                    coveredCount = Math.addExact(coveredCount, captured.getCount());
                }
            }
        }

        if (collectedResults != null) {
            for (ItemStack collected : collectedResults) {
                if (collected == null || collected.isEmpty()) continue;
                ItemStack remaining = collected.copy();
                if (coveredCount > 0 && expectedWorldOutput != null
                        && !expectedWorldOutput.isEmpty()
                        && ItemStack.isSameItem(expectedWorldOutput, remaining)) {
                    int covered = Math.min(coveredCount, remaining.getCount());
                    remaining.shrink(covered);
                    coveredCount -= covered;
                }
                if (!remaining.isEmpty()) merged.add(remaining);
            }
        }
        return List.copyOf(merged);
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
