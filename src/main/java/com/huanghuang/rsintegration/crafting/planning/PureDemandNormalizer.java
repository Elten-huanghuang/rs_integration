package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;

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
                    demand.nbtMatchMode());
            AccumulatedDemand accumulated = merged.get(key);
            if (accumulated == null) {
                merged.put(key, new AccumulatedDemand(
                        List.copyOf(new LinkedHashSet<>(demand.alternatives())), demand.count(),
                        demand.nbtMatchMode()));
            } else {
                accumulated.add(demand.count());
            }
        }

        List<IngredientRef> normalized = new ArrayList<>(merged.size());
        for (AccumulatedDemand demand : merged.values()) {
            normalized.add(new IngredientRef(demand.alternatives, demand.count,
                    demand.nbtMatchMode));
        }
        return List.copyOf(normalized);
    }

    private record DemandKey(Set<MaterialRef> alternatives,
                             ImmutableRecipeGraph.NbtMatchMode nbtMatchMode) {}

    private static final class AccumulatedDemand {
        private final List<MaterialRef> alternatives;
        private final ImmutableRecipeGraph.NbtMatchMode nbtMatchMode;
        private int count;

        private AccumulatedDemand(List<MaterialRef> alternatives, int count,
                                  ImmutableRecipeGraph.NbtMatchMode nbtMatchMode) {
            this.alternatives = alternatives;
            this.count = count;
            this.nbtMatchMode = nbtMatchMode;
        }

        private void add(int additional) {
            long total = (long) count + additional;
            count = total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        }
    }
}
