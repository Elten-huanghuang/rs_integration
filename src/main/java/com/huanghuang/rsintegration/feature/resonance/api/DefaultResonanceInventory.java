package com.huanghuang.rsintegration.feature.resonance.api;

import com.huanghuang.rsintegration.api.ResourceMatchMode;
import com.huanghuang.rsintegration.api.resonance.ResonanceDiagnostic;
import com.huanghuang.rsintegration.api.resonance.ResonanceDiagnosticCode;
import com.huanghuang.rsintegration.api.resonance.ResonanceEntry;
import com.huanghuang.rsintegration.api.resonance.ResonanceExtraction;
import com.huanghuang.rsintegration.api.resonance.ResonanceInsertion;
import com.huanghuang.rsintegration.api.resonance.ResonanceInventory;
import com.huanghuang.rsintegration.api.resonance.ResonanceResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApiStatus.Internal
final class DefaultResonanceInventory implements ResonanceInventory {
    private final ServerPlayer player;
    private final List<ResonanceStorageView> views;
    private final List<SourceEntry> sources;
    private final Map<Long, SourceEntry> byId;
    private final List<String> backendIds;
    private final long snapshotRevision;
    private final long stored;
    private final long capacity;

    DefaultResonanceInventory(
            ServerPlayer player,
            List<ResonanceStorageView> views
    ) {
        this.player = player;
        this.views = List.copyOf(views);
        this.snapshotRevision = liveRevision(this.views);

        List<SourceEntry> entries = new ArrayList<>();
        Map<Long, SourceEntry> indexed = new LinkedHashMap<>();
        Set<String> backends = new LinkedHashSet<>();
        long totalStored = 0L;
        long totalCapacity = 0L;
        long nextId = 0L;
        for (ResonanceStorageView view : views) {
            backends.add(view.backendId());
            totalStored = saturatedAdd(totalStored, view.getStored());
            totalCapacity = saturatedAdd(totalCapacity, view.getCapacity());
            for (ResonanceStorageView.StoredStack storedStack
                    : view.storedStacks()) {
                ItemStack stack = storedStack.stack();
                if (stack.isEmpty()) continue;
                SourceEntry source = new SourceEntry(
                        nextId++, view, storedStack.slot(), stack);
                entries.add(source);
                indexed.put(source.entryId(), source);
            }
        }
        this.sources = List.copyOf(entries);
        this.byId = Map.copyOf(indexed);
        this.backendIds = List.copyOf(backends);
        this.stored = totalStored;
        this.capacity = totalCapacity;
    }

    @Override
    public long revision() {
        return snapshotRevision;
    }

    @Override
    public List<String> backendIds() {
        return backendIds;
    }

    @Override
    public long stored() {
        return stored;
    }

    @Override
    public long capacity() {
        return capacity;
    }

    @Override
    public List<ResonanceEntry> entries() {
        return sources.stream()
                .map(SourceEntry::publicEntry)
                .toList();
    }

    @Override
    public List<ResonanceEntry> find(
            ItemStack template,
            ResourceMatchMode matchMode
    ) {
        if (template == null || template.isEmpty() || matchMode == null) {
            return List.of();
        }
        return sources.stream()
                .filter(source -> matches(
                        source.stack(), template, matchMode))
                .map(SourceEntry::publicEntry)
                .toList();
    }

    @Override
    public boolean hasItem(
            ItemStack template,
            ResourceMatchMode matchMode
    ) {
        return !find(template, matchMode).isEmpty();
    }

    @Override
    public long count(
            ItemStack template,
            ResourceMatchMode matchMode
    ) {
        if (template == null || template.isEmpty() || matchMode == null) {
            return 0L;
        }
        long count = 0L;
        for (SourceEntry source : sources) {
            if (matches(source.stack(), template, matchMode)) {
                count = saturatedAdd(count, source.stack().getCount());
            }
        }
        return count;
    }

    @Override
    public ResonanceResult<ResonanceExtraction> extract(
            long entryId,
            int amount,
            boolean simulate
    ) {
        if (amount <= 0) {
            return rejected(
                    ResonanceDiagnosticCode.INVALID_REQUEST,
                    "extraction_amount_must_be_positive",
                    false
            );
        }
        try {
            ResonanceDiagnostic stateFailure = mutationStateFailure();
            if (stateFailure != null) {
                return ResonanceResult.rejected(stateFailure);
            }
            SourceEntry source = byId.get(entryId);
            if (source == null) {
                return rejected(
                        ResonanceDiagnosticCode.ENTRY_NOT_FOUND,
                        "resonance_entry_does_not_belong_to_this_snapshot",
                        false
                );
            }
            if (amount > source.stack().getCount()) {
                return rejected(
                        ResonanceDiagnosticCode.INVALID_REQUEST,
                        "extraction_amount_exceeds_entry_count",
                        false
                );
            }

            ItemStack simulated = source.view().extractExactView(
                    source.slot(),
                    source.stack(),
                    amount,
                    true
            );
            if (!sameStack(simulated, source.stack(), amount)) {
                return rejected(
                        ResonanceDiagnosticCode.EXTRACTION_FAILED,
                        "resonance_backend_could_not_extract_the_exact_entry",
                        true
                );
            }
            if (simulate) {
                return ResonanceResult.success(
                        new ResonanceExtraction(
                                simulated, snapshotRevision));
            }

            ItemStack extracted = source.view().extractExactView(
                    source.slot(),
                    source.stack(),
                    amount,
                    false
            );
            if (!sameStack(extracted, source.stack(), amount)) {
                if (!extracted.isEmpty()) {
                    source.view().insertView(
                            source.slot(),
                            extracted,
                            extracted.getCount(),
                            false
                    );
                }
                return rejected(
                        ResonanceDiagnosticCode.EXTRACTION_FAILED,
                        "resonance_backend_returned_a_partial_or_changed_extraction",
                        true
                );
            }
            return ResonanceResult.success(
                    new ResonanceExtraction(
                            extracted, liveRevision(views)));
        } catch (RuntimeException | LinkageError failure) {
            return rejected(
                    ResonanceDiagnosticCode.INTERNAL_ERROR,
                    "resonance_backend_extraction_failed",
                    true
            );
        }
    }

    @Override
    public ResonanceResult<ResonanceInsertion> insert(
            ItemStack stack,
            boolean simulate
    ) {
        if (stack == null || stack.isEmpty()) {
            return rejected(
                    ResonanceDiagnosticCode.INVALID_REQUEST,
                    "inserted_stack_must_not_be_empty",
                    false
            );
        }
        try {
            ResonanceDiagnostic stateFailure = mutationStateFailure();
            if (stateFailure != null) {
                return ResonanceResult.rejected(stateFailure);
            }
            ItemStack remainder = stack.copy();
            for (ResonanceStorageView view : views) {
                if (remainder.isEmpty()) break;
                remainder = view.insertUnassigned(
                        remainder,
                        remainder.getCount(),
                        simulate
                );
            }
            return ResonanceResult.success(new ResonanceInsertion(
                    remainder,
                    simulate ? snapshotRevision : liveRevision(views)
            ));
        } catch (RuntimeException | LinkageError failure) {
            return rejected(
                    ResonanceDiagnosticCode.INSERTION_FAILED,
                    "resonance_backend_insertion_failed",
                    true
            );
        }
    }

    @Nullable
    private ResonanceDiagnostic mutationStateFailure() {
        if (player.isRemoved() || player.server == null) {
            return ResonanceDiagnostic.error(
                    ResonanceDiagnosticCode.PLAYER_UNAVAILABLE,
                    "player_is_no_longer_available",
                    false
            );
        }
        if (!player.server.isSameThread()) {
            return ResonanceDiagnostic.error(
                    ResonanceDiagnosticCode.MAIN_THREAD_REQUIRED,
                    "resonance_mutation_must_run_on_the_server_thread",
                    true
            );
        }
        long current = liveRevision(views);
        if (current != snapshotRevision) {
            return ResonanceDiagnostic.error(
                    ResonanceDiagnosticCode.STALE_SNAPSHOT,
                    "resonance_storage_changed_after_snapshot",
                    true
            );
        }
        return null;
    }

    private static long liveRevision(
            List<ResonanceStorageView> views
    ) {
        long revision = 0xcbf29ce484222325L;
        for (ResonanceStorageView view : views) {
            revision ^= view.backendId().hashCode();
            revision *= 0x100000001b3L;
            revision ^= view.contentRevision();
            revision *= 0x100000001b3L;
        }
        return revision & Long.MAX_VALUE;
    }

    private static boolean matches(
            ItemStack actual,
            ItemStack expected,
            ResourceMatchMode mode
    ) {
        if (actual.isEmpty() || expected.isEmpty()
                || !ItemStack.isSameItem(actual, expected)) {
            return false;
        }
        return switch (mode) {
            case ANY_IDENTITY -> true;
            case EXACT_IDENTITY ->
                    ItemStack.isSameItemSameTags(actual, expected);
            case PARTIAL_IDENTITY -> compoundContains(
                    actual.getTag(),
                    expected.getTag()
            );
        };
    }

    private static boolean compoundContains(
            @Nullable CompoundTag actual,
            @Nullable CompoundTag expected
    ) {
        if (expected == null || expected.isEmpty()) return true;
        if (actual == null) return false;
        for (String key : expected.getAllKeys()) {
            Tag required = expected.get(key);
            Tag present = actual.get(key);
            if (required == null || present == null
                    || required.getId() != present.getId()) {
                return false;
            }
            if (required instanceof CompoundTag requiredCompound
                    && present instanceof CompoundTag presentCompound) {
                if (!compoundContains(presentCompound, requiredCompound)) {
                    return false;
                }
            } else if (!required.equals(present)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameStack(
            ItemStack actual,
            ItemStack expected,
            int amount
    ) {
        return !actual.isEmpty()
                && actual.getCount() == amount
                && ItemStack.isSameItemSameTags(actual, expected);
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right
                ? Long.MAX_VALUE : left + right;
    }

    private static <T> ResonanceResult<T> rejected(
            ResonanceDiagnosticCode code,
            String detail,
            boolean retryable
    ) {
        return ResonanceResult.rejected(
                ResonanceDiagnostic.error(code, detail, retryable));
    }

    private record SourceEntry(
            long entryId,
            ResonanceStorageView view,
            int slot,
            ItemStack stack
    ) {
        private SourceEntry {
            stack = stack.copy();
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }

        private ResonanceEntry publicEntry() {
            return new ResonanceEntry(entryId, stack);
        }
    }
}
