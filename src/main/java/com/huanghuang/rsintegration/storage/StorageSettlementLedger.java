package com.huanghuang.rsintegration.storage;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Pure accounting for reserved, extracted, settled, and recovered storage assets.
 * It never performs native storage or inventory operations.
 * Instances are mutable and must be confined to one orchestration thread.
 */
public final class StorageSettlementLedger {
    public enum State {
        OPEN,
        COMMITTING,
        COMMITTED,
        RECOVERY_REQUIRED,
        RECOVERING,
        ROLLED_BACK,
        INDETERMINATE,
        SETTLED
    }

    public enum EntryState {
        RESERVED,
        COMMITTED,
        RECOVERY_REQUIRED,
        RECOVERED,
        INDETERMINATE,
        SETTLED
    }

    public record EntryId(UUID ledgerId, long value) {
        public EntryId {
            Objects.requireNonNull(ledgerId, "ledgerId");
            if (value <= 0) throw new IllegalArgumentException("ledger entry id must be positive");
        }
    }

    public record RecoveryAssetId(UUID ledgerId, long value) {
        public RecoveryAssetId {
            Objects.requireNonNull(ledgerId, "ledgerId");
            if (value <= 0) throw new IllegalArgumentException("recovery asset id must be positive");
        }
    }

    /** One physical stack fragment held by the caller until it is recovered. */
    public record RecoveryAsset(RecoveryAssetId id, ItemStack stack) {
        public RecoveryAsset {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(stack, "stack");
            if (stack.isEmpty()) throw new IllegalArgumentException("recovery asset must not be empty");
            stack = stack.copy();
        }

        @Override
        public ItemStack stack() { return stack.copy(); }
    }

    public record ReservationToken(List<EntryId> entryIds) {
        public ReservationToken {
            entryIds = List.copyOf(Objects.requireNonNull(entryIds, "entryIds"));
            if (new HashSet<>(entryIds).size() != entryIds.size()) {
                throw new IllegalArgumentException("reservation token contains duplicate entry ids");
            }
        }
    }

    public record EntrySnapshot(EntryId id, StorageReservationSource source,
                                StorageItemKey key, long requestedAmount,
                                long confirmedExtractedAmount, long confirmedRecoveryAmount,
                                long recoveredAmount,
                                EntryState state, StorageDiagnosticCode diagnosticCode) {
        public EntrySnapshot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(diagnosticCode, "diagnosticCode");
            if (requestedAmount <= 0 || confirmedExtractedAmount < 0
                    || confirmedExtractedAmount > requestedAmount
                    || confirmedRecoveryAmount < confirmedExtractedAmount
                    || recoveredAmount < 0 || recoveredAmount > confirmedRecoveryAmount) {
                throw new IllegalArgumentException("invalid ledger entry amounts");
            }
            if (state == EntryState.INDETERMINATE
                    && diagnosticCode == StorageDiagnosticCode.NONE) {
                throw new IllegalArgumentException("indeterminate ledger entry requires a diagnostic");
            }
        }

        public long recoveryRemaining() {
            return confirmedRecoveryAmount - recoveredAmount;
        }
    }

    private static final class Asset {
        private final RecoveryAssetId id;
        private final ItemStack stack;
        private long recoveredAmount;

        private Asset(RecoveryAssetId id, ItemStack stack) {
            this.id = id;
            this.stack = stack.copy();
        }

        private long remaining() { return stack.getCount() - recoveredAmount; }

        private RecoveryAsset view() {
            return new RecoveryAsset(id, stack.copyWithCount(Math.toIntExact(remaining())));
        }
    }

    private static final class Entry {
        private final EntryId id;
        private final StorageReservationSource source;
        private final StorageItemKey key;
        private final long requestedAmount;
        private List<Asset> recoveryAssets = List.of();
        private long confirmedExtractedAmount;
        private long confirmedRecoveryAmount;
        private long recoveredAmount;
        private EntryState state = EntryState.RESERVED;
        private StorageDiagnosticCode diagnosticCode = StorageDiagnosticCode.NONE;
        private boolean uncertainMutation;

        private Entry(EntryId id, StorageReservationSource source,
                      StorageItemKey key, long requestedAmount) {
            this.id = id;
            this.source = source;
            this.key = key;
            this.requestedAmount = requestedAmount;
        }

        private EntrySnapshot snapshot() {
            return new EntrySnapshot(id, source, key, requestedAmount,
                    confirmedExtractedAmount, confirmedRecoveryAmount,
                    recoveredAmount, state, diagnosticCode);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<EntryId, Entry> entriesById = new LinkedHashMap<>();
    private final Map<RecoveryAssetId, Asset> assetsById = new LinkedHashMap<>();
    private final UUID ledgerId = UUID.randomUUID();
    private State state = State.OPEN;
    private long nextId = 1;
    private long nextAssetId = 1;

    public State state() { return state; }

    public int size() { return entries.size(); }

    public int reservationMark() {
        requireState(State.OPEN);
        return entries.size();
    }

    public EntryId reserve(StorageReservationSource source, StorageItemKey key, long amount) {
        requireState(State.OPEN);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(key, "key");
        if (amount <= 0) throw new IllegalArgumentException("reservation amount must be positive");
        if (source.storageReference().isPresent()
                && !source.storageReference().orElseThrow().backendId().equals(key.backendId())) {
            throw new IllegalArgumentException("storage source and item key use different backends");
        }
        if (nextId == Long.MAX_VALUE) throw new IllegalStateException("ledger entry id space exhausted");
        Entry entry = new Entry(new EntryId(ledgerId, nextId++), source, key, amount);
        entries.add(entry);
        entriesById.put(entry.id, entry);
        return entry.id;
    }

    public ReservationToken tokenSince(int mark) {
        requireState(State.OPEN);
        validateMark(mark);
        return new ReservationToken(entries.subList(mark, entries.size()).stream()
                .map(entry -> entry.id).toList());
    }

    public void cancelReservationsSince(int mark) {
        requireState(State.OPEN);
        validateMark(mark);
        while (entries.size() > mark) {
            Entry removed = entries.remove(entries.size() - 1);
            entriesById.remove(removed.id);
            for (Asset asset : removed.recoveryAssets) assetsById.remove(asset.id);
        }
    }

    public void beginCommit() {
        requireState(State.OPEN);
        state = State.COMMITTING;
    }

    public void recordExtraction(EntryId id, StorageOperationResult result) {
        Entry entry = requireEntry(id);
        recordExtraction(id, result, stack ->
                StorageItemKey.fromItemStack(entry.key.backendId(), stack));
    }

    /** Validates confirmed fragments using the source session's backend-specific identity rules. */
    public void recordExtraction(EntryId id, StorageOperationResult result, StorageSession session) {
        Objects.requireNonNull(session, "session");
        recordExtraction(id, result, session::itemKey);
    }

    /** Uses the backend's authoritative key mapper to validate every confirmed fragment. */
    public void recordExtraction(EntryId id, StorageOperationResult result,
                                 Function<ItemStack, StorageItemKey> keyMapper) {
        requireState(State.COMMITTING);
        Entry entry = requireEntry(id);
        if (entry.state != EntryState.RESERVED) {
            throw new IllegalStateException("extraction already recorded for entry " + id.value());
        }
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(keyMapper, "keyMapper");
        if (result.kind() != StorageOperationResult.Kind.EXTRACT
                || result.mode() != StorageOperationMode.PERFORM
                || result.requestedAmount() != entry.requestedAmount) {
            throw new IllegalArgumentException("extraction result does not match reservation");
        }

        List<ItemStack> confirmed = copyStacks(result.extractedStacks());
        if (confirmed.stream().map(stack -> Objects.requireNonNull(
                        keyMapper.apply(stack.copy()), "mapped storage key"))
                .anyMatch(returnedKey -> !entry.key.equals(returnedKey))) {
            throw new IllegalArgumentException("extraction result contains another item identity");
        }
        List<ItemStack> unexpectedRecovery = copyStacks(result.recoveryStacks());
        List<Asset> assets = new ArrayList<>(confirmed.size() + unexpectedRecovery.size());
        for (ItemStack stack : confirmed) assets.add(createAsset(stack));
        for (ItemStack stack : unexpectedRecovery) assets.add(createAsset(stack));
        entry.recoveryAssets = List.copyOf(assets);
        entry.confirmedExtractedAmount = stackCount(confirmed);
        entry.confirmedRecoveryAmount = stackCount(confirmed, unexpectedRecovery);
        entry.diagnosticCode = result.diagnosticCode();
        if (result.transferredAmount().isEmpty()) {
            entry.uncertainMutation = true;
            entry.state = EntryState.INDETERMINATE;
        } else if (result.status() == StorageOperationStatus.SUCCESS
                && result.transferredAmount().orElseThrow() == entry.requestedAmount) {
            entry.state = EntryState.COMMITTED;
        } else {
            entry.state = EntryState.RECOVERY_REQUIRED;
        }
    }

    public State finishCommit() {
        requireState(State.COMMITTING);
        if (entries.stream().anyMatch(entry -> entry.state == EntryState.RESERVED)) {
            throw new IllegalStateException("not every reservation has an extraction result");
        }
        if (entries.stream().anyMatch(entry -> entry.uncertainMutation)) {
            state = State.INDETERMINATE;
        } else if (entries.stream().anyMatch(
                entry -> entry.state == EntryState.RECOVERY_REQUIRED)) {
            state = State.RECOVERY_REQUIRED;
        } else {
            state = State.COMMITTED;
        }
        return state;
    }

    public void settle(ReservationToken token) {
        requireState(State.COMMITTED);
        List<Entry> owned = requireTokenEntries(token);
        for (Entry entry : owned) {
            if (entry.state != EntryState.COMMITTED) {
                throw new IllegalStateException("entry is not committed: " + entry.id.value());
            }
        }
        for (Entry entry : owned) {
            entry.state = EntryState.SETTLED;
        }
        if (entries.stream().allMatch(entry -> entry.state == EntryState.SETTLED)) {
            state = State.SETTLED;
        }
    }

    public void settleAll() {
        requireState(State.COMMITTED);
        for (Entry entry : entries) {
            if (entry.state != EntryState.COMMITTED && entry.state != EntryState.SETTLED) {
                throw new IllegalStateException("ledger contains a non-committed entry");
            }
        }
        for (Entry entry : entries) {
            entry.state = EntryState.SETTLED;
        }
        state = State.SETTLED;
    }

    public void beginRecovery() {
        requireState(State.COMMITTED, State.RECOVERY_REQUIRED, State.INDETERMINATE);
        for (Entry entry : entries) {
            if (entry.state != EntryState.SETTLED && !entry.uncertainMutation
                    && entry.recoveredAmount == entry.confirmedRecoveryAmount) {
                entry.state = EntryState.RECOVERED;
            }
        }
        state = State.RECOVERING;
    }

    /** Remaining confirmed assets, including unexpected native recovery stacks. */
    public List<RecoveryAsset> recoveryAssets(EntryId id) {
        requireState(State.RECOVERING);
        Entry entry = requireEntry(id);
        return entry.recoveryAssets.stream().filter(asset -> asset.remaining() > 0)
                .map(Asset::view).toList();
    }

    public void recordRecovery(EntryId id, RecoveryAssetId assetId,
                               StorageOperationResult result) {
        requireState(State.RECOVERING);
        Entry entry = requireRecoverableEntry(id);
        Asset asset = requireRecoveryAsset(entry, assetId);
        Objects.requireNonNull(result, "result");
        if (result.kind() != StorageOperationResult.Kind.INSERT
                || result.mode() != StorageOperationMode.PERFORM
                || result.requestedAmount() > asset.remaining()) {
            throw new IllegalArgumentException("insert result exceeds recoverable amount");
        }
        if (result.transferredAmount().isEmpty()) {
            entry.uncertainMutation = true;
            entry.state = EntryState.INDETERMINATE;
            entry.diagnosticCode = result.diagnosticCode();
            return;
        }
        if (result.diagnosticCode() != StorageDiagnosticCode.NONE) {
            entry.diagnosticCode = result.diagnosticCode();
        }
        long transferred = result.transferredAmount().orElseThrow();
        if (transferred > 0) recordRecoveredAmount(entry, asset, transferred);
    }

    /** Records a confirmed fallback delivery to inventory, an escrow, or a world drop. */
    public void recordRecoveredAmount(EntryId id, RecoveryAssetId assetId, long amount) {
        requireState(State.RECOVERING);
        Entry entry = requireRecoverableEntry(id);
        recordRecoveredAmount(entry, requireRecoveryAsset(entry, assetId), amount);
    }

    public State finishRecovery() {
        requireState(State.RECOVERING);
        if (entries.stream().anyMatch(entry -> entry.uncertainMutation)) {
            state = State.INDETERMINATE;
        } else if (entries.stream().anyMatch(entry -> entry.state != EntryState.SETTLED
                && recoveryRemaining(entry) > 0)) {
            state = State.RECOVERY_REQUIRED;
        } else {
            for (Entry entry : entries) {
                if (entry.state != EntryState.SETTLED) entry.state = EntryState.RECOVERED;
            }
            state = State.ROLLED_BACK;
        }
        return state;
    }

    public List<EntrySnapshot> entries() {
        return entries.stream().map(Entry::snapshot).toList();
    }

    public Optional<EntrySnapshot> entry(EntryId id) {
        Entry entry = entriesById.get(Objects.requireNonNull(id, "id"));
        return entry == null ? Optional.empty() : Optional.of(entry.snapshot());
    }

    private void recordRecoveredAmount(Entry entry, Asset asset, long amount) {
        if (amount <= 0 || amount > asset.remaining()) {
            throw new IllegalArgumentException("recovered amount exceeds confirmed assets");
        }
        asset.recoveredAmount = Math.addExact(asset.recoveredAmount, amount);
        entry.recoveredAmount = Math.addExact(entry.recoveredAmount, amount);
        if (!entry.uncertainMutation && recoveryRemaining(entry) == 0) {
            entry.state = EntryState.RECOVERED;
        }
    }

    private Entry requireRecoverableEntry(EntryId id) {
        Entry entry = requireEntry(id);
        if (entry.state == EntryState.RESERVED || entry.state == EntryState.SETTLED) {
            throw new IllegalStateException("entry is not recoverable: " + id.value());
        }
        if (recoveryRemaining(entry) <= 0) {
            throw new IllegalStateException("entry has no confirmed assets left to recover: " + id.value());
        }
        return entry;
    }

    private Asset requireRecoveryAsset(Entry entry, RecoveryAssetId assetId) {
        Asset asset = assetsById.get(Objects.requireNonNull(assetId, "assetId"));
        if (asset == null || !entry.recoveryAssets.contains(asset)) {
            throw new IllegalArgumentException("recovery asset does not belong to entry");
        }
        if (asset.remaining() <= 0) {
            throw new IllegalStateException("recovery asset is already complete");
        }
        return asset;
    }

    private List<Entry> requireTokenEntries(ReservationToken token) {
        Objects.requireNonNull(token, "token");
        List<Entry> owned = new ArrayList<>(token.entryIds().size());
        for (EntryId id : token.entryIds()) owned.add(requireEntry(id));
        return owned;
    }

    private Entry requireEntry(EntryId id) {
        Entry entry = entriesById.get(Objects.requireNonNull(id, "id"));
        if (entry == null) throw new IllegalArgumentException("unknown ledger entry: " + id.value());
        return entry;
    }

    private void validateMark(int mark) {
        if (mark < 0 || mark > entries.size()) {
            throw new IllegalArgumentException("reservation mark out of range: " + mark);
        }
    }

    private void requireState(State... allowed) {
        for (State candidate : allowed) {
            if (state == candidate) return;
        }
        throw new IllegalStateException("ledger state " + state
                + " is not one of " + Set.of(allowed));
    }

    private static long recoveryRemaining(Entry entry) {
        return entry.confirmedRecoveryAmount - entry.recoveredAmount;
    }

    private Asset createAsset(ItemStack stack) {
        if (nextAssetId == Long.MAX_VALUE) {
            throw new IllegalStateException("recovery asset id space exhausted");
        }
        Asset asset = new Asset(new RecoveryAssetId(ledgerId, nextAssetId++), stack);
        assetsById.put(asset.id, asset);
        return asset;
    }

    private static long stackCount(List<ItemStack> stacks) {
        long total = 0;
        for (ItemStack stack : stacks) total = Math.addExact(total, stack.getCount());
        return total;
    }

    private static long stackCount(List<ItemStack> first, List<ItemStack> second) {
        return Math.addExact(stackCount(first), stackCount(second));
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        List<ItemStack> copied = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) copied.add(stack.copy());
        return List.copyOf(copied);
    }

}
