package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Most recent recursive-plan demand context used for JEI shortage decoration. */
public final class JeiCraftingPlanContext {
    public static final JeiCraftingPlanContext INSTANCE = new JeiCraftingPlanContext();
    private final Map<String, Demand> demands = new LinkedHashMap<>();
    @Nullable private StorageReference reference;
    private boolean active;

    private JeiCraftingPlanContext() {}

    public synchronized void activate(PlanResponse plan) {
        demands.clear();
        reference = plan.storageReference();
        for (var entry : plan.materials().entrySet()) {
            PlanResponse.Availability availability = entry.getValue();
            if (availability.needed() <= 0 && availability.missingCount() <= 0) continue;
            ItemStack stack = entry.getKey().stack(1);
            demands.put(JeiNetworkInventoryPacket.key(stack), new Demand(
                    availability.needed(), availability.available(), availability.missingCount(),
                    stack.hasTag()));
        }
        active = true;
    }

    public synchronized void clear() {
        demands.clear();
        reference = null;
        active = false;
    }

    @Nullable
    public synchronized Demand demand(ItemStack stack, JeiNetworkItemCache inventory) {
        if (!active) return null;
        if (reference != null && inventory.isConnected() && !inventory.matches(reference)) {
            // A plan belongs to exactly one storage network. Once the player changes
            // networks, discard it instead of allowing it to reappear later as stale data.
            clear();
            return null;
        }
        Demand exact = demands.get(JeiNetworkInventoryPacket.key(stack));
        if (exact != null || !stack.hasTag()) return exact;
        return demands.get(JeiNetworkInventoryPacket.key(new ItemStack(stack.getItem())));
    }

    public record Demand(long needed, long plannedAvailable, long missing, boolean exactNbt) {
        /** Rebase the plan-time shortage against the incrementally synchronized live stock. */
        public long remainingMissing(long currentAvailable) {
            if (missing <= 0) return 0L;
            long requiredAtPlanTime = plannedAvailable + missing;
            return Math.max(0L, requiredAtPlanTime - Math.max(0L, currentAvailable));
        }
    }
}
