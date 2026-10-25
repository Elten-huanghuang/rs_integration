package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.api.recipe.FluidRequirement;
import com.huanghuang.rsintegration.storage.StorageFluidKey;
import com.huanghuang.rsintegration.storage.StorageFluidSnapshot;
import com.huanghuang.rsintegration.storage.StoredFluid;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

final class ExternalFluidMatcher {
    private ExternalFluidMatcher() {}

    @Nullable
    static StorageFluidKey find(
            FluidRequirement requirement,
            StorageFluidSnapshot snapshot,
            Map<StorageFluidKey, Long> reserved
    ) {
        List<StorageFluidKey> alternatives = requirement.fluid().alternatives()
                .stream()
                .map(resource -> new StorageFluidKey(
                        snapshot.backendId(),
                        resource.id(),
                        resource.identity()
                ))
                .toList();
        for (StorageFluidKey alternative : alternatives) {
            long available = Math.max(
                    0L,
                    snapshot.countExact(alternative)
                            - reserved.getOrDefault(alternative, 0L)
            );
            if (available >= requirement.amount()) return alternative;
        }
        for (StoredFluid fluid : snapshot.fluids()) {
            boolean accepted = alternatives.stream().anyMatch(template ->
                    template.fluidId().equals(fluid.key().fluidId())
                            && switch (requirement.fluid().matchMode()) {
                        case ANY_IDENTITY -> true;
                        case EXACT_IDENTITY ->
                                template.equals(fluid.key());
                        case PARTIAL_IDENTITY ->
                                ExternalRecipeProjection.compoundContains(
                                        fluid.key().tag(), template.tag());
                    });
            if (!accepted) continue;
            long available = Math.max(
                    0L,
                    snapshot.countExact(fluid.key())
                            - reserved.getOrDefault(fluid.key(), 0L)
            );
            if (available >= requirement.amount()) return fluid.key();
        }
        return null;
    }

    static void reserve(
            Map<StorageFluidKey, Long> reserved,
            StorageFluidKey key,
            long amount
    ) {
        long current = reserved.getOrDefault(key, 0L);
        reserved.put(key, Long.MAX_VALUE - current < amount
                ? Long.MAX_VALUE : current + amount);
    }
}
