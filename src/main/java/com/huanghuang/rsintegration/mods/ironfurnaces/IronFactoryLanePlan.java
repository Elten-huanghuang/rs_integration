package com.huanghuang.rsintegration.mods.ironfurnaces;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** One physical factory window: one reserved material, distributed over leased lane pairs. */
public record IronFactoryLanePlan(List<Lane> lanes, int operations) {
    private static final int FIRST_INPUT = 7;
    private static final int LANE_COUNT = 6;

    public IronFactoryLanePlan {
        lanes = List.copyOf(lanes);
        if (operations <= 0 || lanes.isEmpty()
                || lanes.stream().mapToInt(lane -> lane.input().getCount()).sum() != operations) {
            throw new IllegalArgumentException("factory lane plan does not match reserved operations");
        }
    }

    public static IronFactoryLanePlan plan(ItemStack material, int operations, int laneCapacity,
                                           boolean spreadAcrossLanes,
                                           boolean[] leased, boolean[] available) {
        if (material == null || material.isEmpty() || operations <= 0 || laneCapacity <= 0
                || leased == null || available == null || leased.length != LANE_COUNT
                || available.length != LANE_COUNT) return null;
        int capacity = Math.min(laneCapacity, material.getMaxStackSize());
        List<Lane> lanes = new ArrayList<>();
        int remaining = operations;
        int usable = 0;
        for (int index = 0; index < LANE_COUNT; index++) {
            if (leased[index] && available[index]) usable++;
        }
        for (int index = 0; index < LANE_COUNT && remaining > 0; index++) {
            if (!leased[index] || !available[index]) continue;
            int count = spreadAcrossLanes
                    ? Math.min(capacity, (remaining + usable - 1) / usable)
                    : Math.min(capacity, remaining);
            lanes.add(new Lane(index, FIRST_INPUT + index, FIRST_INPUT + LANE_COUNT + index,
                    material.copyWithCount(count)));
            remaining -= count;
            usable--;
        }
        return remaining == 0 ? new IronFactoryLanePlan(lanes, operations) : null;
    }

    public record Lane(int index, int inputSlot, int outputSlot, ItemStack input) {
        public Lane {
            if (index < 0 || index >= LANE_COUNT || inputSlot != FIRST_INPUT + index
                    || outputSlot != inputSlot + LANE_COUNT || input == null || input.isEmpty()) {
                throw new IllegalArgumentException("invalid factory lane mapping");
            }
            input = input.copy();
        }
    }
}
