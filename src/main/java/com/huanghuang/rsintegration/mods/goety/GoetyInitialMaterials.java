package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.AllocationId;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource.InitialPool;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** 用当前库存中符合配方的实物更新 Goety 初始材料分配。 */
public final class GoetyInitialMaterials {
    private GoetyInitialMaterials() {}

    public static CraftPlanGraph refresh(CraftPlanGraph graph, boolean goetyTerminal,
                                         ServerPlayer player, Supplier<CraftStorageEndpoint> endpoint) {
        return rebind(graph, goetyTerminal,
                types -> MaterialSources.listAvailableForTypes(player, endpoint.get(), types));
    }

    static CraftPlanGraph rebind(CraftPlanGraph graph, boolean goetyTerminal,
                                 Function<Set<Item>, Map<StackKey, Long>> currentStock) {
        Set<InputPortId> flexiblePorts = new HashSet<>();
        graph.nodes().stream().filter(node -> ModIds.GOETY.equals(node.modTypeId()))
                .forEach(node -> node.inputs().forEach(input -> {
                    if (input.role() == DemandRole.CONSUMED && ordinary(input.ingredient())) {
                        flexiblePorts.add(input.id());
                    }
                }));
        if (!goetyTerminal && flexiblePorts.isEmpty()) return graph;

        Map<MaterialKey, Long> required = new LinkedHashMap<>();
        Set<MaterialKey> fixed = new HashSet<>();
        for (MaterialAllocation allocation : graph.allocations()) {
            if (allocation.source() instanceof InitialPool) {
                required.merge(allocation.material(), allocation.quantity(), Math::addExact);
                if (!flexiblePorts.contains(allocation.consumer())) fixed.add(allocation.material());
            }
        }
        for (RootDemand root : graph.rootDemands()) {
            boolean flexible = goetyTerminal && root.role() == DemandRole.CONSUMED && ordinary(root.ingredient());
            for (RootAllocation allocation : root.allocations()) {
                if (allocation.source() instanceof InitialPool) {
                    required.merge(allocation.material(), allocation.quantity(), Math::addExact);
                    if (!flexible) fixed.add(allocation.material());
                }
            }
        }
        Set<Item> types = new HashSet<>();
        for (MaterialKey material : required.keySet()) {
            if (!fixed.contains(material)) types.add(material.item());
        }
        if (types.isEmpty()) return graph;

        Map<StackKey, Long> stock = currentStock.apply(types);
        Map<MaterialKey, Long> spare = new HashMap<>();
        stock.forEach((key, amount) -> {
            if (!types.contains(key.item())) return;
            MaterialKey material = new MaterialKey(key.item(), key.tag());
            long free = amount - required.getOrDefault(material, 0L);
            if (free > 0L) spare.put(material, free);
        });
        List<MaterialKey> candidates = new ArrayList<>(spare.keySet());
        candidates.sort((first, second) -> Objects.toString(first.tag(), "")
                .compareTo(Objects.toString(second.tag(), "")));
        Map<MaterialKey, Deque<RootAllocation>> replacements = new HashMap<>();
        required.forEach((material, quantity) -> {
            long exact = stock.getOrDefault(new StackKey(material.item(), material.tag()), 0L);
            if (fixed.contains(material) || exact >= quantity) return;
            Deque<RootAllocation> supply = new ArrayDeque<>();
            if (exact > 0L) supply.add(new RootAllocation(new InitialPool(material), material, exact));
            long remaining = quantity - exact;
            for (MaterialKey candidate : candidates) {
                long take = Math.min(remaining, spare.get(candidate));
                if (take <= 0L || candidate.item() != material.item()) continue;
                supply.add(new RootAllocation(new InitialPool(candidate), candidate, take));
                remaining -= take;
                if (remaining == 0L) break;
            }
            if (remaining == 0L) {
                replacements.put(material, supply);
                for (RootAllocation part : supply) {
                    if (!part.material().equals(material)) {
                        spare.compute(part.material(), (key, amount) -> amount - part.quantity());
                    }
                }
            }
        });
        if (replacements.isEmpty()) return graph;

        long nextId = graph.allocations().stream().mapToLong(allocation -> allocation.id().value())
                .max().orElse(-1L);
        List<MaterialAllocation> allocations = new ArrayList<>(graph.allocations().size());
        for (MaterialAllocation allocation : graph.allocations()) {
            if (!(allocation.source() instanceof InitialPool) || !replacements.containsKey(allocation.material())) {
                allocations.add(allocation);
                continue;
            }
            AllocationId id = allocation.id();
            for (RootAllocation part : take(replacements.get(allocation.material()), allocation.quantity())) {
                if (id == null) {
                    nextId = Math.incrementExact(nextId);
                    id = new AllocationId(nextId);
                }
                allocations.add(new MaterialAllocation(id, allocation.consumer(), part.source(),
                        part.material(), part.quantity()));
                id = null;
            }
        }
        List<RootDemand> roots = graph.rootDemands().stream().map(root -> {
            List<RootAllocation> supply = new ArrayList<>();
            for (RootAllocation allocation : root.allocations()) {
                Deque<RootAllocation> replacement = replacements.get(allocation.material());
                if (replacement != null && allocation.source() instanceof InitialPool) {
                    supply.addAll(take(replacement, allocation.quantity()));
                } else {
                    supply.add(allocation);
                }
            }
            return new RootDemand(root.ingredient(), root.quantity(), root.unresolvedQuantity(),
                    root.displayHint(), supply, root.role());
        }).toList();
        return new CraftPlanGraph(graph.version(), graph.nodes(), allocations, roots,
                graph.unresolvedDemands(), graph.topologicalOrder(), graph.planningRevision());
    }

    private static List<RootAllocation> take(Deque<RootAllocation> supply, long remaining) {
        List<RootAllocation> parts = new ArrayList<>();
        while (remaining > 0L) {
            RootAllocation part = supply.removeFirst();
            long taken = Math.min(remaining, part.quantity());
            parts.add(new RootAllocation(part.source(), part.material(), taken));
            if (taken < part.quantity()) {
                supply.addFirst(new RootAllocation(part.source(), part.material(), part.quantity() - taken));
            }
            remaining -= taken;
        }
        return parts;
    }

    private static boolean ordinary(Ingredient ingredient) {
        return ingredient.getClass() == Ingredient.class && ingredient.getItems().length == 1;
    }
}
