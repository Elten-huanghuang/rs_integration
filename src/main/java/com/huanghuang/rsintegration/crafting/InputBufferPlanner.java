package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将物料需求映射到输入缓冲的物理槽位，保留输入与输出端口身份。
 * 同一消耗条目声明多个槽位时，按各槽容量分摊加工份数。
 */
public final class InputBufferPlanner {
    private InputBufferPlanner() {}

    public static InputBufferPlan plan(int requestedOperations, int configuredCapacity,
                                       List<InputBufferContract.InputSlot> inputSpecs,
                                       List<OutputContract.Port> outputSpecs) {
        int requested = Math.max(0, requestedOperations);
        int capacity = Math.max(0, configuredCapacity);
        if (requested == 0 || capacity == 0 || inputSpecs == null || inputSpecs.isEmpty()) {
            return InputBufferPlan.none();
        }
        Map<String, List<InputBufferContract.InputSlot>> groups = groupInputSlots(inputSpecs);

        int operations = Math.min(requested, capacity);
        List<InputBufferPlan.InputSlot> inputs = new ArrayList<>(inputSpecs.size());
        for (List<InputBufferContract.InputSlot> group : groups.values()) {
            InputBufferContract.InputSlot spec = group.get(0);
            boolean reusable = spec.consumption().reusableWithinWorkerBatch();
            if (reusable) {
                if (spec.perOperation() > spec.capacity()) return InputBufferPlan.none();
                continue;
            }
            if (spec.perOperation() > 0) {
                long groupCapacity = group.stream()
                        .mapToLong(slot -> slot.capacity() / spec.perOperation()).sum();
                operations = (int) Math.min(operations, groupCapacity);
            }
        }
        if (operations <= 0) return InputBufferPlan.none();

        Map<Integer, Integer> slotOperations = new HashMap<>();
        for (List<InputBufferContract.InputSlot> group : groups.values()) {
            int remaining = operations;
            long remainingCapacity = group.stream()
                    .mapToLong(slot -> slot.capacity() / Math.max(1, slot.perOperation())).sum();
            for (int i = 0; i < group.size(); i++) {
                InputBufferContract.InputSlot spec = group.get(i);
                int slotCapacity = spec.capacity() / Math.max(1, spec.perOperation());
                remainingCapacity -= slotCapacity;
                // 尽量均分加工份数；容量较小的通道放不下时，由其他通道承担余量。
                int share = (int) (((long) remaining + group.size() - i - 1)
                        / (group.size() - i));
                int assigned = group.size() == 1 ? operations
                        : (int) Math.min(slotCapacity,
                                Math.max(share, remaining - remainingCapacity));
                slotOperations.put(spec.slot(), assigned);
                remaining -= assigned;
            }
        }
        for (InputBufferContract.InputSlot spec : inputSpecs) {
            int assigned = slotOperations.get(spec.slot());
            if (assigned == 0) continue;
            int count = spec.consumption().reusableWithinWorkerBatch()
                    ? spec.perOperation()
                    : Math.multiplyExact(spec.perOperation(), assigned);
            inputs.add(new InputBufferPlan.InputSlot(spec.entryId(), spec.slot(),
                    spec.prototype().copyWithCount(count), spec.perOperation(),
                    spec.consumption()));
        }
        return new InputBufferPlan(operations, inputs, outputSpecs);
    }

    private static Map<String, List<InputBufferContract.InputSlot>> groupInputSlots(
            List<InputBufferContract.InputSlot> specs) {
        Set<Integer> seen = new HashSet<>();
        Map<String, List<InputBufferContract.InputSlot>> groups = new LinkedHashMap<>();
        for (InputBufferContract.InputSlot spec : specs) {
            if (!seen.add(spec.slot())) throw new IllegalArgumentException(
                    "duplicate input slot " + spec.slot());
            List<InputBufferContract.InputSlot> group = groups.computeIfAbsent(
                    spec.entryId(), ignored -> new ArrayList<>());
            if (!group.isEmpty() && !group.get(0).sharesMaterialWith(spec)) {
                throw new IllegalArgumentException(
                        "duplicate input entry id " + spec.entryId());
            }
            group.add(spec);
        }
        return groups;
    }

}
