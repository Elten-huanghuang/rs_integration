package com.huanghuang.rsintegration.crafting.external;

import com.huanghuang.rsintegration.api.recipe.FluidRequirement;
import com.huanghuang.rsintegration.storage.StorageFluidKey;
import com.huanghuang.rsintegration.storage.StorageFluidSnapshot;
import com.huanghuang.rsintegration.storage.StoredFluid;

import javax.annotation.Nullable;
import java.util.List;
import java.util.LinkedHashSet;
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
            if (canSupply(snapshot, reserved, alternative, requirement.amount())) return alternative;
        }
        var candidates = new LinkedHashSet<>(snapshot.infiniteFluids());
        for (StoredFluid fluid : snapshot.fluids()) candidates.add(fluid.key());
        for (StorageFluidKey candidate : candidates) {
            boolean accepted = alternatives.stream().anyMatch(template ->
                    template.fluidId().equals(candidate.fluidId())
                            && switch (requirement.fluid().matchMode()) {
                        case ANY_IDENTITY -> true;
                        case EXACT_IDENTITY ->
                                template.equals(candidate);
                        case PARTIAL_IDENTITY ->
                                ExternalRecipeProjection.compoundContains(
                                        candidate.tag(), template.tag());
                    });
            if (accepted && canSupply(snapshot, reserved, candidate, requirement.amount())) return candidate;
        }
        return null;
    }

    private static boolean canSupply(StorageFluidSnapshot snapshot, Map<StorageFluidKey, Long> reserved,
                                     StorageFluidKey key, long amount) {
        return snapshot.infiniteFluids().contains(key)
                || snapshot.countExact(key) - reserved.getOrDefault(key, 0L) >= amount;
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
