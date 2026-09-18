package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Allocates already-stored concrete item variants to terminal recipe inputs. */
public final class DirectMaterialAllocator {
    private DirectMaterialAllocator() {}

    public static Result allocate(List<IngredientSpec> specs, Map<StackKey, Integer> available) {
        List<IndexedSpec> demands = new ArrayList<>();
        long totalDemand = 0L;
        for (int i = 0; i < specs.size(); i++) {
            IngredientSpec spec = specs.get(i);
            if (spec == null || spec.isEmpty()) continue;
            demands.add(new IndexedSpec(i, spec));
            totalDemand += spec.count();
        }
        if (demands.isEmpty()) return new Result(true, List.of(), -1, 0);

        Set<Item> relevantItems = new LinkedHashSet<>();
        boolean requiresFullScan = false;
        for (IndexedSpec demand : demands) {
            ItemStack[] candidates = candidateItems(demand.spec());
            if (candidates.length == 0) {
                requiresFullScan = true;
                break;
            }
            for (ItemStack candidate : candidates) {
                if (!candidate.isEmpty()) relevantItems.add(candidate.getItem());
            }
        }
        final boolean scanEveryItem = requiresFullScan;
        List<Map.Entry<StackKey, Integer>> supplies = available.entrySet().stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getValue() != null && entry.getValue() > 0
                        && (scanEveryItem || relevantItems.contains(entry.getKey().item())))
                .toList();
        MaterialVariantPreferences.Snapshot preferences =
                MaterialVariantPreferences.snapshot();
        Map<Item, List<Integer>> supplyIndicesByItem = new HashMap<>();
        for (int i = 0; i < supplies.size(); i++) {
            supplyIndicesByItem.computeIfAbsent(supplies.get(i).getKey().item(), ignored ->
                    new ArrayList<>()).add(i);
        }
        int source = 0;
        int demandStart = 1;
        int supplyStart = demandStart + demands.size();
        int sink = supplyStart + supplies.size();
        Dinic flow = new Dinic(sink + 1);
        List<DemandEdges> demandEdges = new ArrayList<>(demands.size());

        for (int i = 0; i < demands.size(); i++) {
            IndexedSpec indexed = demands.get(i);
            int demandNode = demandStart + i;
            Edge sourceEdge = flow.addEdge(source, demandNode, indexed.spec().count());
            List<AllocationEdge> allocations = new ArrayList<>();
            Set<Integer> candidateSupplyIndices = new LinkedHashSet<>();
            ItemStack[] candidates = candidateItems(indexed.spec());
            if (candidates.length == 0) {
                for (int j = 0; j < supplies.size(); j++) candidateSupplyIndices.add(j);
            } else {
                for (ItemStack candidate : candidates) {
                    if (!candidate.isEmpty()) {
                        candidateSupplyIndices.addAll(supplyIndicesByItem.getOrDefault(
                                candidate.getItem(), List.of()));
                    }
                }
            }
            List<Integer> orderedSupplyIndices = new ArrayList<>(candidateSupplyIndices);
            orderedSupplyIndices.sort(Comparator
                    .comparingInt((Integer index) ->
                            preferences.rank(supplies.get(index).getKey().item()))
                    .thenComparing(Comparator.comparingInt((Integer index) ->
                            supplies.get(index).getValue()).reversed()));
            for (int j : orderedSupplyIndices) {
                StackKey material = supplies.get(j).getKey();
                boolean matches;
                try {
                    matches = IngredientMatcher.test(indexed.spec().ingredient(), material);
                } catch (RuntimeException | LinkageError ignored) {
                    matches = false;
                }
                if (!matches) continue;
                Edge edge = flow.addEdge(demandNode, supplyStart + j, indexed.spec().count());
                allocations.add(new AllocationEdge(material, edge));
            }
            demandEdges.add(new DemandEdges(indexed, sourceEdge, allocations));
        }
        for (int j = 0; j < supplies.size(); j++) {
            flow.addEdge(supplyStart + j, sink, supplies.get(j).getValue());
        }

        long supplied = flow.maxFlow(source, sink);
        if (supplied != totalDemand) {
            for (DemandEdges demand : demandEdges) {
                if (demand.sourceEdge().capacity > 0) {
                    return new Result(false, List.of(), demand.indexed().index(),
                            (int) Math.min(Integer.MAX_VALUE, demand.sourceEdge().capacity));
                }
            }
            return new Result(false, List.of(), -1,
                    (int) Math.min(Integer.MAX_VALUE, totalDemand - supplied));
        }

        List<Allocation> result = new ArrayList<>();
        for (DemandEdges demand : demandEdges) {
            for (AllocationEdge allocation : demand.allocations()) {
                long used = allocation.edge().originalCapacity - allocation.edge().capacity;
                if (used > 0) {
                    result.add(new Allocation(demand.indexed().index(), allocation.material(),
                            (int) Math.min(Integer.MAX_VALUE, used)));
                }
            }
        }
        return new Result(true, List.copyOf(result), -1, 0);
    }

    private static ItemStack[] candidateItems(IngredientSpec spec) {
        try {
            return spec.ingredient().getItems();
        } catch (RuntimeException | LinkageError ignored) {
            return new ItemStack[0];
        }
    }

    public record Allocation(int ingredientIndex, StackKey material, int count) {}

    public record Result(boolean feasible, List<Allocation> allocations,
                         int missingIngredientIndex, int missingCount) {
        public Result {
            allocations = List.copyOf(allocations);
        }
    }

    private record IndexedSpec(int index, IngredientSpec spec) {}
    private record AllocationEdge(StackKey material, Edge edge) {}
    private record DemandEdges(IndexedSpec indexed, Edge sourceEdge,
                               List<AllocationEdge> allocations) {}

    private static final class Dinic {
        private final List<List<Edge>> graph;
        private final int[] level;
        private final int[] next;

        private Dinic(int nodes) {
            graph = new ArrayList<>(nodes);
            for (int i = 0; i < nodes; i++) graph.add(new ArrayList<>());
            level = new int[nodes];
            next = new int[nodes];
        }

        private Edge addEdge(int from, int to, long capacity) {
            Edge forward = new Edge(to, graph.get(to).size(), capacity);
            Edge reverse = new Edge(from, graph.get(from).size(), 0L);
            graph.get(from).add(forward);
            graph.get(to).add(reverse);
            return forward;
        }

        private long maxFlow(int source, int sink) {
            long total = 0L;
            while (buildLevels(source, sink)) {
                Arrays.fill(next, 0);
                long pushed;
                while ((pushed = push(source, sink, Long.MAX_VALUE)) > 0) total += pushed;
            }
            return total;
        }

        private boolean buildLevels(int source, int sink) {
            Arrays.fill(level, -1);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            level[source] = 0;
            queue.add(source);
            while (!queue.isEmpty()) {
                int node = queue.removeFirst();
                for (Edge edge : graph.get(node)) {
                    if (edge.capacity <= 0 || level[edge.to] >= 0) continue;
                    level[edge.to] = level[node] + 1;
                    queue.addLast(edge.to);
                }
            }
            return level[sink] >= 0;
        }

        private long push(int node, int sink, long limit) {
            if (node == sink) return limit;
            List<Edge> edges = graph.get(node);
            for (; next[node] < edges.size(); next[node]++) {
                Edge edge = edges.get(next[node]);
                if (edge.capacity <= 0 || level[edge.to] != level[node] + 1) continue;
                long pushed = push(edge.to, sink, Math.min(limit, edge.capacity));
                if (pushed <= 0) continue;
                edge.capacity -= pushed;
                graph.get(edge.to).get(edge.reverseIndex).capacity += pushed;
                return pushed;
            }
            return 0L;
        }
    }

    private static final class Edge {
        private final int to;
        private final int reverseIndex;
        private final long originalCapacity;
        private long capacity;

        private Edge(int to, int reverseIndex, long capacity) {
            this.to = to;
            this.reverseIndex = reverseIndex;
            this.originalCapacity = capacity;
            this.capacity = capacity;
        }
    }
}
