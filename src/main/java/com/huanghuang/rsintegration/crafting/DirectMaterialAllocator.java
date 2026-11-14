package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Allocates already-stored concrete item variants to terminal recipe inputs. */
public final class DirectMaterialAllocator {
    private DirectMaterialAllocator() {}

    public static Result allocate(List<IngredientSpec> specs,
                                  Map<StackKey, ? extends Number> supplied) {
        List<IndexedDemand> demands = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            IngredientSpec spec = specs.get(i);
            if (spec == null || spec.isEmpty()) continue;
            demands.add(new IndexedDemand(i, spec.ingredient(), spec.count()));
        }
        return allocateDemands(demands, supplied);
    }

    public static Result allocateScaled(List<ScaledIngredientSpec> specs,
                                        Map<StackKey, ? extends Number> supplied) {
        List<IndexedDemand> demands = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            ScaledIngredientSpec spec = specs.get(i);
            if (spec == null || spec.isEmpty()) continue;
            demands.add(new IndexedDemand(i, spec.ingredient(), spec.count()));
        }
        return allocateDemands(demands, supplied);
    }

    /**
     * 机器槽位版本：同一个 tag 输入必须由单一变体填满，不能跨变体凑数。
     * 预览若按普通流分配，会出现“预览可行、执行缺料”的假阳性。
     */
    public static Result allocateScaledSingleVariant(
            List<ScaledIngredientSpec> specs, Map<StackKey, ? extends Number> supplied) {
        List<IndexedDemand> demands = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            ScaledIngredientSpec spec = specs.get(i);
            if (spec == null || spec.isEmpty()) continue;
            demands.add(new IndexedDemand(i, spec.ingredient(), spec.count()));
        }

        List<Integer> variantDemandIndices = new ArrayList<>();
        List<List<Ingredient>> variantOptions = new ArrayList<>();
        for (int i = 0; i < demands.size(); i++) {
            IndexedDemand demand = demands.get(i);
            // 单件需求天然只能分到一种变体，交给流分配即可，避免无意义的组合枚举。
            if (demand.count() <= 1L) continue;
            ItemStack[] candidates = candidateItems(demand.ingredient());
            if (candidates.length <= 1) continue;
            LinkedHashMap<StackKey, Ingredient> variants = new LinkedHashMap<>();
            boolean strictNbt = IngredientMatcher.requiresNbt(demand.ingredient());
            for (ItemStack candidate : candidates) {
                if (candidate == null || candidate.isEmpty()) continue;
                Ingredient option = CraftingResolver.ingredientOf(candidate, strictNbt);
                long available = 0L;
                for (var supply : supplied.entrySet()) {
                    if (supply.getKey() != null && supply.getValue() != null
                            && supply.getValue().longValue() > 0L
                            && IngredientMatcher.test(option, supply.getKey())) {
                        available = saturatedAdd(available, supply.getValue().longValue());
                    }
                }
                // 这里只分配现有库存；不足以填满槽位的候选不应消耗组合搜索预算。
                if (available < demand.count()) continue;
                variants.putIfAbsent(StackKey.of(candidate, candidate.hasTag()),
                        option);
            }
            if (variants.isEmpty()) return new Result(false, List.of(), demand.index(), demand.count());
            if (variants.size() == 1) {
                demands.set(i, new IndexedDemand(demand.index(), variants.values().iterator().next(),
                        demand.count()));
                continue;
            }
            variantDemandIndices.add(i);
            variantOptions.add(new ArrayList<>(variants.values()));
        }
        if (variantDemandIndices.isEmpty()) return allocateDemands(demands, supplied);

        return allocateSingleVariantAt(demands, variantDemandIndices,
                variantOptions, supplied, new int[] {0});
    }

    private static Result allocateSingleVariantAt(
            List<IndexedDemand> demands, List<Integer> variantDemandIndices,
            List<List<Ingredient>> variantOptions,
            Map<StackKey, ? extends Number> supplied, int[] attempts) {
        // 先整体分配现有库存；已经满足单一变体约束的槽位无需再枚举组合。
        Result allocation = allocateDemands(demands, supplied);
        if (!allocation.feasible()) return allocation;
        Map<Integer, List<Allocation>> allocationsByInput = new HashMap<>();
        for (Allocation fragment : allocation.allocations()) {
            allocationsByInput.computeIfAbsent(fragment.ingredientIndex(), ignored -> new ArrayList<>())
                    .add(fragment);
        }
        int selected = -1;
        for (int i = 0; i < variantDemandIndices.size(); i++) {
            List<Allocation> fragments = allocationsByInput.get(
                    demands.get(variantDemandIndices.get(i)).index());
            // 使用候选谓词判断整槽，保留严格 NBT 和兼容匹配的语义。
            boolean singleVariant = variantOptions.get(i).stream().anyMatch(option ->
                    fragments.stream().allMatch(fragment -> IngredientMatcher.test(option, fragment.material())));
            if (!singleVariant
                    && (selected < 0 || variantOptions.get(i).size() < variantOptions.get(selected).size())) {
                selected = i;
            }
        }
        if (selected < 0) return allocation;

        int demandIndex = variantDemandIndices.get(selected);
        IndexedDemand original = demands.get(demandIndex);
        Result lastFailure = null;
        for (Ingredient option : variantOptions.get(selected)) {
            attempts[0]++;
            if (attempts[0] > 512) break;
            demands.set(demandIndex, new IndexedDemand(original.index(), option, original.count()));
            Result result = allocateSingleVariantAt(demands, variantDemandIndices,
                    variantOptions, supplied, attempts);
            if (result.feasible()) return result;
            lastFailure = result;
        }
        demands.set(demandIndex, original);
        return lastFailure != null ? lastFailure
                : new Result(false, List.of(), original.index(), original.count());
    }

    private static Result allocateDemands(List<IndexedDemand> demands,
                                          Map<StackKey, ? extends Number> supplied) {
        Map<StackKey, Long> available = new LinkedHashMap<>();
        supplied.forEach((key, count) -> {
            if (key != null && count != null && count.longValue() > 0L) {
                available.put(key, count.longValue());
            }
        });
        long totalDemand = 0L;
        for (IndexedDemand demand : demands) {
            totalDemand = saturatedAdd(totalDemand, demand.count());
        }
        if (demands.isEmpty()) return new Result(true, List.of(), -1, 0);

        Set<Item> relevantItems = new LinkedHashSet<>();
        boolean requiresFullScan = false;
        for (IndexedDemand demand : demands) {
            ItemStack[] candidates = candidateItems(demand.ingredient());
            if (candidates.length == 0) {
                requiresFullScan = true;
                break;
            }
            for (ItemStack candidate : candidates) {
                if (!candidate.isEmpty()) relevantItems.add(candidate.getItem());
            }
        }
        final boolean scanEveryItem = requiresFullScan;
        List<Map.Entry<StackKey, Long>> supplies = available.entrySet().stream()
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
            IndexedDemand indexed = demands.get(i);
            int demandNode = demandStart + i;
            Edge sourceEdge = flow.addEdge(source, demandNode, indexed.count());
            List<AllocationEdge> allocations = new ArrayList<>();
            Set<Integer> candidateSupplyIndices = new LinkedHashSet<>();
            ItemStack[] candidates = candidateItems(indexed.ingredient());
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
                    .thenComparing(Comparator.comparingLong((Integer index) ->
                            supplies.get(index).getValue()).reversed()));
            for (int j : orderedSupplyIndices) {
                StackKey material = supplies.get(j).getKey();
                boolean matches;
                try {
                    matches = IngredientMatcher.test(indexed.ingredient(), material);
                } catch (RuntimeException | LinkageError ignored) {
                    matches = false;
                }
                if (!matches) continue;
                Edge edge = flow.addEdge(demandNode, supplyStart + j, indexed.count());
                allocations.add(new AllocationEdge(material, edge));
            }
            demandEdges.add(new DemandEdges(indexed, sourceEdge, allocations));
        }
        for (int j = 0; j < supplies.size(); j++) {
            flow.addEdge(supplyStart + j, sink, supplies.get(j).getValue());
        }

        long totalSupplied = flow.maxFlow(source, sink);
        if (totalSupplied != totalDemand) {
            for (DemandEdges demand : demandEdges) {
                if (demand.sourceEdge().capacity > 0) {
                    return new Result(false, List.of(), demand.indexed().index(),
                            demand.sourceEdge().capacity);
                }
            }
            return new Result(false, List.of(), -1, totalDemand - totalSupplied);
        }

        List<Allocation> result = new ArrayList<>();
        for (DemandEdges demand : demandEdges) {
            for (AllocationEdge allocation : demand.allocations()) {
                long used = allocation.edge().originalCapacity - allocation.edge().capacity;
                if (used > 0) {
                    result.add(new Allocation(demand.indexed().index(), allocation.material(),
                            used));
                }
            }
        }
        return new Result(true, List.copyOf(result), -1, 0);
    }

    private static ItemStack[] candidateItems(Ingredient ingredient) {
        try {
            return ingredient.getItems();
        } catch (RuntimeException | LinkageError ignored) {
            return new ItemStack[0];
        }
    }

    public record Allocation(int ingredientIndex, StackKey material, long count) {}

    public record Result(boolean feasible, List<Allocation> allocations,
                         int missingIngredientIndex, long missingCount) {
        public Result {
            allocations = List.copyOf(allocations);
        }
    }

    private record IndexedDemand(int index, Ingredient ingredient, long count) {}
    private record AllocationEdge(StackKey material, Edge edge) {}
    private record DemandEdges(IndexedDemand indexed, Edge sourceEdge,
                               List<AllocationEdge> allocations) {}

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) return left;
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

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
