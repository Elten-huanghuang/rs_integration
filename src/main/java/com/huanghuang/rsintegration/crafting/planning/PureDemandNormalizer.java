package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Merges equivalent immutable demands before bounded recipe traversal. */
final class PureDemandNormalizer {
    private PureDemandNormalizer() {}

    static List<IngredientRef> mergeEquivalent(List<IngredientRef> demands) {
        if (demands.size() < 2) return List.copyOf(demands);

        Map<DemandKey, AccumulatedDemand> merged = new LinkedHashMap<>();
        for (IngredientRef demand : demands) {
            DemandKey key = new DemandKey(Set.copyOf(demand.alternatives()),
                    demand.nbtMatchMode(), demand.role(), demand.recursive());
            AccumulatedDemand accumulated = merged.get(key);
            if (accumulated == null) {
                merged.put(key, new AccumulatedDemand(
                        List.copyOf(new LinkedHashSet<>(demand.alternatives())), demand.count(),
                        demand.nbtMatchMode(), demand.role(), demand.recursive()));
            } else {
                accumulated.add(demand.count());
            }
        }

        List<IngredientRef> normalized = new ArrayList<>(merged.size());
        for (AccumulatedDemand demand : merged.values()) {
            normalized.add(new IngredientRef(demand.alternatives, demand.count,
                    demand.nbtMatchMode, demand.role, demand.recursive));
        }
        return List.copyOf(normalized);
    }

    private record DemandKey(Set<MaterialRef> alternatives,
                             NbtMatchMode nbtMatchMode, DemandRole role, boolean recursive) {}

    private static final class AccumulatedDemand {
        private final List<MaterialRef> alternatives;
        private final NbtMatchMode nbtMatchMode;
        private final DemandRole role;
        private final boolean recursive;
        private long count;

        private AccumulatedDemand(List<MaterialRef> alternatives, long count,
                                  NbtMatchMode nbtMatchMode, DemandRole role, boolean recursive) {
            this.alternatives = alternatives;
            this.count = count;
            this.nbtMatchMode = nbtMatchMode;
            this.role = role;
            this.recursive = recursive;
        }

        private void add(long additional) {
            count = Long.MAX_VALUE - count < additional ? Long.MAX_VALUE : count + additional;
        }
    }
}
