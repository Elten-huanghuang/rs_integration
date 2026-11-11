package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.mixin.refinedstorage.MonitorAmountsAccessor;
import com.huanghuang.rsintegration.mixin.refinedstorage.MonitorErrorAccessor;
import com.huanghuang.rsintegration.mixin.refinedstorage.MonitorFluidAccessor;
import com.huanghuang.rsintegration.mixin.refinedstorage.MonitorItemAccessor;
import com.refinedmods.refinedstorage.api.autocrafting.craftingmonitor.ICraftingMonitorElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 仅消费已经同步的数据。RS 数量是材料量，RSI 节点数量是加工次数，两者不混用。 */
public record MonitorStepEntry(String key, int elementIndex, ItemStack item, FluidStack fluid,
                               Component title, Component state, Component counts, List<Component> tooltip,
                               String searchText, int priority, boolean active, double completedFraction) {
    public static List<MonitorStepEntry> forRsi(CraftProgressSnapshot snapshot) {
        var entries = new ArrayList<MonitorStepEntry>();
        for (int index = 0; index < snapshot.nodes().size(); index++) {
            var node = snapshot.nodes().get(index);
            ItemStack item = node.displayOutput();
            Component title = item.isEmpty() ? Component.literal(node.recipeId()) : item.getHoverName();
            Component state = Component.translatable(node.draining() ? "rsi.monitor.draining"
                    : node.reason() != CraftProgressSnapshot.Reason.NONE ? node.reason().translationKey()
                    : "rsi.progress.step.state." + node.state().name().toLowerCase(Locale.ROOT));
            long pending = Math.max(0L, (long) node.totalOperations() - node.completedOperations() - node.runningOperations());
            Component counts = Component.translatable("rsi.monitor.step_counts", node.completedOperations(),
                    node.totalOperations(), node.runningOperations());
            var tooltip = new ArrayList<Component>();
            tooltip.add(title);
            tooltip.add(state);
            tooltip.add(counts);
            tooltip.add(Component.translatable("rsi.monitor.pending_operations", pending));
            if (!node.machineLabel().isBlank()) tooltip.add(Component.literal(node.machineLabel()));
            tooltip.add(Component.literal(node.recipeId()));
            if (!node.technicalDetail().isBlank()) tooltip.add(Component.literal(node.technicalDetail()));
            boolean active = node.draining() || node.runningOperations() > 0 || switch (node.state()) {
                case SUCCEEDED, CANCELLED -> false;
                default -> true;
            };
            int rank = node.state() == CraftProgressSnapshot.NodeState.FAILED ? 0
                    : node.runningOperations() > 0 ? 1
                    : node.reason() != CraftProgressSnapshot.Reason.NONE ? 2 : active ? 3 : 4;
            entries.add(new MonitorStepEntry("node:" + node.nodeId(), index, item, FluidStack.EMPTY,
                    title, state, counts, List.copyOf(tooltip), title.getString() + " " + node.recipeId()
                    + " " + node.modTypeId() + " " + node.machineLabel() + " " + state.getString(), rank, active,
                    node.totalOperations() > 0 ? (double) node.completedOperations() / node.totalOperations() : -1));
        }
        return List.copyOf(entries);
    }

    public static List<MonitorStepEntry> forNative(List<ICraftingMonitorElement> elements) {
        var entries = new ArrayList<MonitorStepEntry>();
        for (int index = 0; index < elements.size(); index++) {
            var original = elements.get(index);
            var base = original instanceof MonitorErrorAccessor error ? error.rsi$base() : original;
            ItemStack item = base instanceof MonitorItemAccessor accessor ? accessor.rsi$stack() : ItemStack.EMPTY;
            FluidStack fluid = base instanceof MonitorFluidAccessor accessor ? accessor.rsi$stack() : FluidStack.EMPTY;
            var tooltip = original.getTooltip();
            Component title = !item.isEmpty() ? item.getHoverName() : !fluid.isEmpty() ? fluid.getDisplayName()
                    : tooltip.isEmpty() ? Component.literal(original.getId().toString()) : tooltip.get(0);
            String resource = !item.isEmpty() ? String.valueOf(ForgeRegistries.ITEMS.getKey(item.getItem()))
                    : !fluid.isEmpty() ? String.valueOf(ForgeRegistries.FLUIDS.getKey(fluid.getFluid())) : original.getId().toString();
            boolean error = original instanceof MonitorErrorAccessor;
            Component state = Component.translatable("rsi.monitor.native_details");
            Component counts = Component.empty();
            int rank = error ? 0 : 3;
            boolean active = true;
            if (base instanceof MonitorAmountsAccessor amount) {
                long running = Math.max(0L, amount.rsi$processing()) + Math.max(0L, amount.rsi$crafting());
                long pending = Math.max(0L, amount.rsi$scheduled());
                long missing = Math.max(0L, amount.rsi$missing());
                active = error || running > 0 || pending > 0 || missing > 0;
                rank = error || missing > 0 ? 0 : running > 0 ? 1 : active ? 3 : 4;
                state = Component.translatable(error || missing > 0 ? "rsi.monitor.native_blocked"
                        : running > 0 ? "rsi.monitor.native_running" : active ? "rsi.monitor.native_pending" : "rsi.monitor.native_stored");
                String unit = fluid.isEmpty() ? "" : " mB";
                counts = Component.translatable("rsi.monitor.native_counts", Math.max(0, amount.rsi$processing()) + unit,
                        Math.max(0, amount.rsi$crafting()) + unit, pending + unit);
                if (!active) counts = Component.translatable("rsi.monitor.native_stored_count", Math.max(0, amount.rsi$stored()) + unit);
            }
            entries.add(new MonitorStepEntry(resource + ":" + original.baseElementHashCode(), index,
                    item, fluid, title, state, counts, List.copyOf(tooltip), title.getString() + " " + resource,
                    rank, active, -1));
        }
        return List.copyOf(entries);
    }

    public static List<MonitorStepEntry> filter(List<MonitorStepEntry> source, String query, boolean onlyActive) {
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        return source.stream().filter(entry -> !onlyActive || entry.active())
                .filter(entry -> entry.searchText().toLowerCase(Locale.ROOT).contains(normalized))
                .sorted(Comparator.comparingInt(MonitorStepEntry::priority)).toList();
    }
}
