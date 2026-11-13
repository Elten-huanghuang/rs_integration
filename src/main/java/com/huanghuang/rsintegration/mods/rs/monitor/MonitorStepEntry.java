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
import java.util.HashMap;

/** 仅消费已经同步的数据。RS 数量是材料量，RSI 节点数量是加工次数，两者不混用。 */
public record MonitorStepEntry(String key, int elementIndex, ItemStack item, FluidStack fluid,
                               Component title, Component state, Component counts, Component machine, List<Component> tooltip,
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
            if (!node.draining() && (node.reason() == CraftProgressSnapshot.Reason.NONE
                    || node.reason() == CraftProgressSnapshot.Reason.WAITING_MATERIALS) && !node.waitingInputs().isEmpty()) {
                state = MonitorDependencyDetails.waitingLabel(node.waitingInputs().get(0));
            }
            long pending = Math.max(0L, (long) node.totalOperations() - node.completedOperations() - node.runningOperations());
            Component counts = Component.translatable("rsi.monitor.step_counts", node.completedOperations(),
                    node.totalOperations(), node.runningOperations());
            var tooltip = new ArrayList<Component>();
            tooltip.add(title);
            tooltip.add(state);
            tooltip.add(counts);
            tooltip.add(Component.translatable("rsi.monitor.pending_operations", pending));
            Component type = Component.translatableWithFallback("rsi.batch.mod." + node.modTypeId(),
                    node.modTypeId().replace('_', ' '));
            Component machine;
            if (node.machineLabel().isBlank()) {
                machine = "generic".equals(node.modTypeId()) ? type
                        : Component.translatable("rsi.monitor.machine_unassigned", type);
            } else {
                String position = node.machineLabel().substring(node.machineLabel().indexOf('@') + 1).strip();
                boolean candidate = node.state() == CraftProgressSnapshot.NodeState.READY
                        || node.state() == CraftProgressSnapshot.NodeState.BLOCKED;
                machine = Component.translatable(candidate ? "rsi.monitor.machine_candidate" : "rsi.monitor.step_machine", type, position);
            }
            tooltip.add(machine);
            if (!node.machineLabel().isBlank()) tooltip.add(Component.literal(node.machineLabel()));
            tooltip.add(Component.literal(node.recipeId()));
            if (!node.technicalDetail().isBlank()) tooltip.add(Component.literal(node.technicalDetail()));
            for (var input : node.waitingInputs()) tooltip.add(MonitorDependencyDetails.waitingLabel(input));
            if (node.machineProgress().known()) tooltip.add(Component.translatable("rsi.monitor.cycle.progress", node.machineProgress().percent()));
            tooltip.add(Component.translatable("rsi.monitor.wait.open_hint"));
            boolean active = node.draining() || node.runningOperations() > 0 || switch (node.state()) {
                case SUCCEEDED, CANCELLED -> false;
                default -> true;
            };
            int rank = MonitorDiagnostics.priority(node);
            entries.add(new MonitorStepEntry("node:" + node.nodeId(), index, item, FluidStack.EMPTY,
                    title, state, counts, machine, List.copyOf(tooltip), title.getString() + " " + node.recipeId()
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
                    item, fluid, title, state, counts, Component.empty(), List.copyOf(tooltip), title.getString() + " " + resource,
                    rank, active, -1));
        }
        return List.copyOf(entries);
    }

    public static List<MonitorStepEntry> filter(List<MonitorStepEntry> source, String query, boolean onlyActive) {
        return filterInOrder(source, query, onlyActive).stream()
                .sorted(Comparator.comparingInt(MonitorStepEntry::priority)).toList();
    }

    public static List<MonitorStepEntry> filterInOrder(List<MonitorStepEntry> source, String query, boolean onlyActive) {
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        return source.stream().filter(entry -> !onlyActive || entry.active())
                .filter(entry -> entry.searchText().toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
    }

    /** 首次按重要程度排列；后续状态更新保留旧位置，新步骤追加到末尾。 */
    public static List<MonitorStepEntry> stabilize(List<MonitorStepEntry> previous, List<MonitorStepEntry> current) {
        var order = new HashMap<String, Integer>();
        for (int index = 0; index < previous.size(); index++) order.put(previous.get(index).key(), index);
        return current.stream().sorted(Comparator.comparingInt(entry -> order.getOrDefault(entry.key(), Integer.MAX_VALUE))).toList();
    }
}
