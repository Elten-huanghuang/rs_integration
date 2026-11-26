package com.huanghuang.rsintegration.storage;

import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 不可变流体会话快照，amount 使用 mB 的 long。 */
public final class StorageFluidSnapshot {
    private final StorageBackendId backendId;
    private final List<StoredFluid> fluids;
    private final Map<StorageFluidKey, Long> amounts;
    private final Set<StorageFluidKey> infiniteFluids;

    public StorageFluidSnapshot(
            StorageBackendId backendId,
            List<StoredFluid> fluids
    ) {
        this(backendId, fluids, Set.of());
    }

    public StorageFluidSnapshot(
            StorageBackendId backendId,
            List<StoredFluid> fluids,
            Set<StorageFluidKey> infiniteFluids
    ) {
        this.backendId = Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(fluids, "fluids");
        this.infiniteFluids = Set.copyOf(infiniteFluids);
        if (this.infiniteFluids.stream().anyMatch(key -> !backendId.equals(key.backendId()))) {
            throw new IllegalArgumentException("infinite fluid source belongs to another backend");
        }
        Map<StorageFluidKey, Long> normalized = new LinkedHashMap<>();
        for (StoredFluid fluid : fluids) {
            Objects.requireNonNull(fluid, "fluid");
            if (!backendId.equals(fluid.key().backendId())) {
                throw new IllegalArgumentException(
                        "fluid snapshot contains another backend");
            }
            normalized.merge(
                    fluid.key(), fluid.amount(), StorageFluidSnapshot::saturatedAdd);
        }
        List<StoredFluid> stored = new ArrayList<>(normalized.size());
        normalized.forEach((key, amount) ->
                stored.add(new StoredFluid(key, amount)));
        this.fluids = List.copyOf(stored);
        this.amounts = Map.copyOf(normalized);
    }

    public StorageBackendId backendId() {
        return backendId;
    }

    public List<StoredFluid> fluids() {
        return fluids;
    }

    public Set<StorageFluidKey> infiniteFluids() {
        return infiniteFluids;
    }

    public long countExact(StorageFluidKey key) {
        return amounts.getOrDefault(Objects.requireNonNull(key, "key"), 0L);
    }

    public long availableExact(StorageFluidKey key) {
        return infiniteFluids.contains(key) ? Long.MAX_VALUE : countExact(key);
    }

    public long countMatching(StorageFluidKey template, boolean exactTag) {
        long total = 0L;
        for (StoredFluid fluid : fluids) {
            if (!fluid.key().fluidId().equals(template.fluidId())) continue;
            if (exactTag && !fluid.key().tag().equals(template.tag())) continue;
            total = saturatedAdd(total, fluid.amount());
        }
        return total;
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right
                ? Long.MAX_VALUE : left + right;
    }
}
