package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stable material declaration shared by planning, reservation, and physical placement.
 *
 * <p>Legacy delegates may continue to expose ordered ingredient lists. New delegates
 * should declare stable entry identities so graph allocations, supplemental materials,
 * and physical input slots no longer depend on concatenating lists by index.</p>
 */
public record MaterialPlan(List<Entry> entries) {
    public enum Allocation {
        GRAPH,
        SUPPLEMENTAL
    }

    public MaterialPlan {
        entries = entries == null ? List.of() : List.copyOf(entries);
        Set<String> ids = new HashSet<>();
        for (Entry entry : entries) {
            if (!ids.add(entry.id())) {
                throw new IllegalArgumentException("duplicate material entry id " + entry.id());
            }
        }
    }

    public static MaterialPlan none() {
        return new MaterialPlan(List.of());
    }

    public List<Entry> graphEntries() {
        return entries.stream().filter(entry -> entry.allocation() == Allocation.GRAPH).toList();
    }

    public List<Entry> supplementalEntries() {
        return entries.stream().filter(entry -> entry.allocation() == Allocation.SUPPLEMENTAL).toList();
    }

    public List<Entry> reusableEntries() {
        return entries.stream().filter(Entry::reusable).toList();
    }

    /** Compatibility projection for graph-owned legacy reservations. */
    public List<IngredientSpec> legacyGraphSpecs() {
        return graphEntries().stream().map(Entry::spec).toList();
    }

    /** Compatibility projection for directly-reserved legacy materials. */
    public List<IngredientSpec> legacySupplementalSpecs() {
        return supplementalEntries().stream().map(Entry::spec).toList();
    }

    /** Compatibility projection for old delegates that require an ordered material list. */
    public List<IngredientSpec> legacySpecs() {
        return entries.stream().map(Entry::spec).toList();
    }

    /**
     * Restores the legacy physical placement order from independently reserved
     * graph and supplemental stacks. Every entry consumes one resolved stack
     * from its own allocation channel.
     */
    public List<ItemStack> mergeLegacyReservations(List<ItemStack> graphStacks,
                                                    List<ItemStack> supplementalStacks) {
        List<ItemStack> graph = graphStacks == null ? List.of() : graphStacks;
        List<ItemStack> supplemental = supplementalStacks == null ? List.of() : supplementalStacks;
        List<ItemStack> ordered = new ArrayList<>(entries.size());
        int graphIndex = 0;
        int supplementalIndex = 0;
        for (Entry entry : entries) {
            ItemStack stack;
            if (entry.allocation() == Allocation.GRAPH) {
                if (graphIndex >= graph.size()) {
                    throw new IllegalArgumentException("missing graph stack for material " + entry.id());
                }
                stack = graph.get(graphIndex++);
            } else {
                if (supplementalIndex >= supplemental.size()) {
                    throw new IllegalArgumentException("missing supplemental stack for material " + entry.id());
                }
                stack = supplemental.get(supplementalIndex++);
            }
            if (stack == null || stack.isEmpty()) {
                throw new IllegalArgumentException("empty reserved stack for material " + entry.id());
            }
            ordered.add(stack.copy());
        }
        if (graphIndex != graph.size() || supplementalIndex != supplemental.size()) {
            throw new IllegalArgumentException("reserved stack count does not match material plan");
        }
        return List.copyOf(ordered);
    }

    /**
     * Builds a stable plan for a legacy ordered list. The physical placement index is
     * recorded only as a compatibility fallback; new delegates should declare slots explicitly.
     */
    public static MaterialPlan fromLegacy(List<IngredientSpec> specs,
                                          List<IBatchDelegate.MaterialReservationScope> scopes) {
        if (specs == null || specs.isEmpty()) return none();
        List<Entry> entries = new ArrayList<>(specs.size());
        for (int index = 0; index < specs.size(); index++) {
            IngredientSpec spec = specs.get(index);
            if (spec == null || spec.isEmpty()) continue;
            boolean reusable = scopes != null && index < scopes.size()
                    && scopes.get(index) == IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE;
            entries.add(new Entry("legacy:material:" + index, spec, Allocation.GRAPH,
                    reusable, index));
        }
        return new MaterialPlan(entries);
    }

    /**
     * Converts legacy graph and supplemental lists without losing the original
     * physical placement order. Each ordered entry must occur exactly once in
     * one reservation partition; callers with more complex semantics should
     * declare entries directly instead of relying on list matching.
     */
    public static MaterialPlan fromLegacyPartitions(
            List<IngredientSpec> orderedSpecs,
            List<IngredientSpec> graphSpecs,
            List<IngredientSpec> supplementalSpecs,
            List<IBatchDelegate.MaterialReservationScope> scopes) {
        if (orderedSpecs == null || orderedSpecs.isEmpty()) return none();
        List<IngredientSpec> remainingGraph = compact(graphSpecs);
        List<IngredientSpec> remainingSupplemental = compact(supplementalSpecs);
        List<Entry> entries = new ArrayList<>(orderedSpecs.size());
        for (int index = 0; index < orderedSpecs.size(); index++) {
            IngredientSpec spec = orderedSpecs.get(index);
            if (spec == null || spec.isEmpty()) continue;
            Allocation allocation = take(remainingGraph, spec)
                    ? Allocation.GRAPH
                    : take(remainingSupplemental, spec) ? Allocation.SUPPLEMENTAL : null;
            if (allocation == null) {
                throw new IllegalArgumentException("legacy material at index " + index
                        + " is absent from graph and supplemental partitions");
            }
            boolean reusable = scopes != null && index < scopes.size()
                    && scopes.get(index) == IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE;
            entries.add(new Entry("legacy:material:" + index, spec, allocation, reusable, index));
        }
        if (!remainingGraph.isEmpty() || !remainingSupplemental.isEmpty()) {
            throw new IllegalArgumentException("legacy reservation partitions contain "
                    + "materials absent from the placement order");
        }
        return new MaterialPlan(entries);
    }

    private static List<IngredientSpec> compact(List<IngredientSpec> specs) {
        if (specs == null || specs.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(specs.stream().filter(spec -> spec != null && !spec.isEmpty()).toList());
    }

    private static boolean take(List<IngredientSpec> candidates, IngredientSpec target) {
        for (int index = 0; index < candidates.size(); index++) {
            IngredientSpec candidate = candidates.get(index);
            if (candidate == target || candidate.equals(target)) {
                candidates.remove(index);
                return true;
            }
        }
        return false;
    }

    public record Entry(String id, IngredientSpec spec, Allocation allocation,
                        boolean reusable, @Nullable Integer inputSlot) {
        public Entry {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("material entry id must not be blank");
            }
            if (spec == null || spec.isEmpty()) {
                throw new IllegalArgumentException("material entry spec must not be empty");
            }
            allocation = allocation == null ? Allocation.GRAPH : allocation;
            if (inputSlot != null && inputSlot < 0) {
                throw new IllegalArgumentException("input slot must be non-negative");
            }
        }
    }
}
