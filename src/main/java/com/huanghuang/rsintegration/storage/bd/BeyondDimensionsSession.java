package com.huanghuang.rsintegration.storage.bd;
import java.lang.reflect.Method;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.huanghuang.rsintegration.storage.*;
import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.fluids.FluidStack;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.RSIntegrationMod;
import java.util.Optional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

final class BeyondDimensionsSession implements StorageSession {
    private static final Cache<String, Boolean> SKIPPED_ITEM_WARNINGS = CacheBuilder.newBuilder()
            .maximumSize(256).expireAfterWrite(1, TimeUnit.MINUTES).build();
    private final Object network;
    private final StorageReference reference;
    /** Snapshot is scoped to one synchronous extraction operation only. */
    private ExtractionSnapshotScope extractionSnapshotScope;

    private static final class ExtractionSnapshotScope {
        private final ServerPlayer player;
        private StorageSnapshotResult snapshot;

        private ExtractionSnapshotScope(ServerPlayer player) {
            this.player = player;
        }
    }

    BeyondDimensionsSession(Object network, StorageReference reference) {
        this.network = Objects.requireNonNull(network, "network");
        this.reference = Objects.requireNonNull(reference, "reference");
    }
    @Override public StorageReference reference() { return reference; }
    @Override public boolean isAvailable() {
        try {
            return BeyondDimensionsReflection.isCurrentNetwork(network, reference.networkId());
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }
    @Override public Set<StorageCapability> capabilities() {
        return Set.of(StorageCapability.ITEM_STORAGE, StorageCapability.FLUID_STORAGE);
    }
    @Override public StorageItemKey itemKey(ItemStack stack) {
        return BeyondDimensionsItemKeys.fromStack(reference.backendId(), stack);
    }

    @Override
    public Optional<StorageItemSubscription> subscribeItemChanges(
            StorageItemChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        try {
            Object nativeStorage = storage();
            return Optional.of(BeyondDimensionsReflection.subscribeItemChanges(
                    network, nativeStorage, reference.networkId(), listener));
        } catch (Exception | LinkageError failure) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-JEI] BD item subscription unavailable", failure);
            return Optional.empty();
        }
    }

    private Object storage() throws Exception {
        return network.getClass().getMethod("getUnifiedStorage").invoke(network);
    }

    @Override public StorageSnapshotResult snapshotItems(ServerPlayer player) {
        return snapshotItems(player, null);
    }

    @Override public StorageSnapshotResult snapshotItems(ServerPlayer player, Set<Item> itemTypes) {
        StorageThreadGuard.requireServerThread(player);
        StoragePermissionResult permission = checkPermission(player, StoragePermission.VIEW);
        if (!permission.allowedAccess()) return snapshotFailure(permission);
        try {
            Object nativeStorage = storage();
            Object list = nativeStorage.getClass().getMethod("getStorage").invoke(nativeStorage);
            return StorageSnapshotResult.success(readSnapshot(reference.backendId(), list, itemTypes,
                    (stack, failure) -> warnSkippedItem(player, stack, failure)));
        } catch (Exception | LinkageError e) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Storage] BD item snapshot failed player={} network={} cause={}",
                    player.getGameProfile().getName(), reference.networkId(), e.toString());
            return StorageSnapshotResult.failure(StorageSnapshotStatus.FAILED,
                    StorageDiagnosticCode.BACKEND_EXCEPTION);
        }
    }

    static StorageSnapshot readSnapshot(StorageBackendId backendId, Object nativeEntries,
                                        Set<Item> itemTypes,
                                        BiConsumer<ItemStack, IllegalArgumentException> skipped) throws Exception {
        StorageSnapshot items = readItemSnapshot(backendId, nativeEntries, itemTypes, skipped);
        if (!ModItems.ALCHEMIST_INK_FLUID.isPresent()
                || itemTypes != null && !itemTypes.contains(ModItems.ALCHEMIST_INK_FLUID.get())) return items;
        return withFluids(items, nativeEntries, ModItems.ALCHEMIST_INK_FLUID.get());
    }

    static StorageSnapshot withFluids(StorageSnapshot items, Object nativeEntries, Item tokenItem) throws Exception {
        List<StoredItem> combined = new ArrayList<>(items.items());
        for (Object entry : (Iterable<?>) nativeEntries) {
            Object key = BeyondDimensionsReflection.key(entry);
            if (!BeyondDimensionsReflection.isFluidKey(key)) continue;
            long amount = BeyondDimensionsReflection.amount(entry);
            FluidStack fluid = BeyondDimensionsReflection.fluidStack(key);
            if (amount <= 0 || fluid.isEmpty()) continue;
            // mB 总量只从 KeyAmount 的 long 字段读取，不能再乘只读堆叠的数量。
            fluid.setAmount(1);
            ItemStack token = InkFluidSupport.token(tokenItem, fluid);
            combined.add(new StoredItem(BeyondDimensionsItemKeys.fromStack(items.backendId(), token), amount));
        }
        return new StorageSnapshot(items.backendId(), combined);
    }

    static StorageSnapshot readItemSnapshot(StorageBackendId backendId, Object nativeEntries,
                                           BiConsumer<ItemStack, IllegalArgumentException> skipped) throws Exception {
        return readItemSnapshot(backendId, nativeEntries, null, skipped);
    }

    static StorageSnapshot readItemSnapshot(StorageBackendId backendId, Object nativeEntries,
                                           Set<Item> itemTypes,
                                           BiConsumer<ItemStack, IllegalArgumentException> skipped) throws Exception {
        if (!(nativeEntries instanceof Iterable<?> values)) {
            throw new IllegalArgumentException("BD storage did not return iterable entries");
        }
        List<StoredItem> items = new ArrayList<>();
        for (Object value : values) {
            Object key = BeyondDimensionsReflection.key(value);
            if (!BeyondDimensionsReflection.isItemKey(key)) continue;
            ItemStack stack = BeyondDimensionsReflection.keyStack(key, itemTypes);
            long amount = BeyondDimensionsReflection.amount(value);
            if (stack.isEmpty() || amount <= 0) continue;
            StorageItemKey itemKey;
            try {
                itemKey = BeyondDimensionsItemKeys.fromStack(backendId, stack);
            } catch (IllegalArgumentException failure) {
                // Exclude only unrepresentable identities, without changing native item NBT.
                skipped.accept(stack, failure);
                continue;
            }
            items.add(new StoredItem(itemKey, amount));
        }
        return new StorageSnapshot(backendId, items);
    }

    private void warnSkippedItem(ServerPlayer player, ItemStack stack, IllegalArgumentException failure) {
        var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String warningKey = reference.backendId() + ":" + reference.networkId() + ":" + itemId;
        if (SKIPPED_ITEM_WARNINGS.asMap().putIfAbsent(warningKey, Boolean.TRUE) != null) return;
        RSIntegrationMod.LOGGER.warn(
                "[RSI-Storage] BD snapshot skipped unsupported item player={} network={} item={} cause={}; "
                        + "native item unchanged, other items remain available (once per minute per network/item)",
                player.getGameProfile().getName(), reference.networkId(), itemId, failure.toString());
    }

    @Override public StoragePermissionResult checkPermission(ServerPlayer player, StoragePermission permission) {
        StorageThreadGuard.requireServerThread(player);
        try {
            if (!BeyondDimensionsReflection.isCurrentNetwork(network, reference.networkId())) {
                return StoragePermissionResult.unavailable();
            }
            int networkId = ((Number) network.getClass().getMethod("getId").invoke(network)).intValue();
            return (BeyondDimensionsReflection.hasPlayerAccess(network, player)
                    || BeyondDimensionsReflection.hasBoundNetworkItem(player, networkId))
                    ? StoragePermissionResult.allowed() : StoragePermissionResult.denied();
        } catch (Exception | LinkageError e) {
            return StoragePermissionResult.failed(StorageDiagnosticCode.PERMISSION_CHECK_FAILED);
        }
    }

    @Override public StorageOperationResult extractExact(ServerPlayer player, StorageItemKey key, long amount, boolean simulate) {
        return extractNative(player, key, amount, simulate, false);
    }

    @Override public StorageOperationResult extractMatching(ServerPlayer player, Ingredient ingredient, long amount, boolean simulate) {
        return withExtractionSnapshotScope(player,
                () -> extractMatchingInScope(player, ingredient, amount, simulate));
    }

    private StorageOperationResult extractMatchingInScope(ServerPlayer player, Ingredient ingredient,
                                                          long amount, boolean simulate) {
        if (amount < 0) throw new IllegalArgumentException("amount must not be negative");
        if (amount == 0) return StorageOperationResult.extracted(mode(simulate), 0, List.of());
        StorageSnapshotResult snapshot;
        try {
            snapshot = snapshotForExtraction(player, extractionItemTypes(ingredient));
        } catch (RuntimeException | LinkageError failure) {
            return StorageOperationResult.failedExtraction(mode(simulate), amount,
                    StorageOperationStatus.FAILED, List.of(), List.of(),
                    StorageDiagnosticCode.INGREDIENT_MATCH_FAILED);
        }
        if (!snapshot.successful()) return StorageOperationResult.failedExtraction(mode(simulate), amount,
                snapshot.status() == StorageSnapshotStatus.DENIED ? StorageOperationStatus.DENIED
                        : snapshot.status() == StorageSnapshotStatus.UNAVAILABLE ? StorageOperationStatus.UNAVAILABLE
                        : StorageOperationStatus.FAILED,
                List.of(), List.of(), snapshot.diagnosticCode());
        StorageSnapshot.MatchResult match = snapshot.snapshot().orElseThrow().match(ingredient);
        if (match.status() == StorageSnapshot.MatchStatus.EMPTY_INGREDIENT) {
            return StorageOperationResult.failedExtraction(mode(simulate), amount,
                    StorageOperationStatus.INVALID_REQUEST, List.of(), List.of());
        }
        if (!match.successful()) {
            return StorageOperationResult.failedExtraction(mode(simulate), amount,
                    StorageOperationStatus.FAILED, List.of(), List.of(), match.diagnosticCode());
        }
        List<ItemStack> result = new ArrayList<>();
        long remaining = amount;
        for (StoredItem item : match.items()) {
            if (remaining <= 0) break;
            StorageOperationResult extracted = extractExact(player, item.key(), Math.min(remaining, item.amount()), simulate);
            result.addAll(extracted.extractedStacks());
            long transferred = extracted.transferredAmount().orElse(0);
            remaining -= transferred;
            if (extracted.status() != StorageOperationStatus.SUCCESS
                    && extracted.status() != StorageOperationStatus.PARTIAL
                    && extracted.status() != StorageOperationStatus.NOT_FOUND) {
                return extracted.status() == StorageOperationStatus.INDETERMINATE
                        ? StorageOperationResult.indeterminateExtraction(amount, result,
                        extracted.recoveryStacks(), extracted.diagnosticCode())
                        : StorageOperationResult.failedExtraction(mode(simulate), amount,
                        extracted.status(), result, extracted.recoveryStacks(),
                        extracted.diagnosticCode());
            }
        }
        return StorageOperationResult.extracted(mode(simulate), amount, result);
    }

    static Set<Item> extractionItemTypes(Ingredient ingredient) {
        return IngredientMatcher.itemTypesForMatching(ingredient);
    }

    private StorageSnapshotResult snapshotForExtraction(ServerPlayer player, Set<Item> itemTypes) {
        ExtractionSnapshotScope scope = extractionSnapshotScope;
        if (scope == null || scope.player != player) return snapshotItems(player, itemTypes);
        if (scope.snapshot == null) scope.snapshot = snapshotItems(player, itemTypes);
        return scope.snapshot;
    }

    private <T> T withExtractionSnapshotScope(ServerPlayer player, Supplier<T> action) {
        ExtractionSnapshotScope previous = extractionSnapshotScope;
        // Nested matching may require item types absent from the outer snapshot.
        ExtractionSnapshotScope scope = new ExtractionSnapshotScope(player);
        extractionSnapshotScope = scope;
        try {
            return action.get();
        } finally {
            // A perform extraction may have mutated native storage. Never let
            // this snapshot escape the synchronous operation boundary.
            scope.snapshot = null;
            extractionSnapshotScope = previous;
        }
    }

    private StorageOperationResult extractNative(ServerPlayer player, StorageItemKey key, long amount, boolean simulate, boolean fuzzy) {
        StorageThreadGuard.requireServerThread(player);
        if (amount < 0) throw new IllegalArgumentException("amount must not be negative");
        if (amount > Integer.MAX_VALUE) return StorageOperationResult.failedExtraction(mode(simulate), amount,
                StorageOperationStatus.UNAVAILABLE, List.of(), List.of());
        if (!reference.backendId().equals(key.backendId())) return StorageOperationResult.failedExtraction(mode(simulate), amount, StorageOperationStatus.INVALID_REQUEST, List.of(), List.of());
        StoragePermissionResult permission = checkPermission(player, StoragePermission.EXTRACT);
        if (!permission.allowedAccess()) return permissionFailureExtraction(mode(simulate), amount, permission);
        Object nativeKey;
        Object nativeStorage;
        Method extract;
        try {
            nativeStorage = storage();
            StorageItemKey storedKey = snapshotForExtraction(player,
                    Set.of(BuiltInRegistries.ITEM.get(key.itemType()))).snapshot()
                    .map(snapshot -> storedPayloadKey(snapshot, key))
                    .orElse(key);
            ItemStack template = ItemStack.of(storedKey.backendPayload());
            nativeKey = InkFluidSupport.isToken(template)
                    ? BeyondDimensionsReflection.fluidKey(InkFluidSupport.fluid(template))
                    : BeyondDimensionsReflection.itemKey(template);
            extract = nativeStorage.getClass().getMethod("extract", Class.forName("com.wintercogs.beyonddimensions.api.storage.key.IStackKey"), long.class, boolean.class, boolean.class);
        } catch (Exception | LinkageError e) {
            return StorageOperationResult.failedExtraction(mode(simulate), amount,
                    StorageOperationStatus.FAILED, List.of(), List.of(),
                    StorageDiagnosticCode.BACKEND_EXCEPTION);
        }
        try {
            Object got = extract.invoke(nativeStorage, nativeKey, amount, simulate, fuzzy);
            long transferred = BeyondDimensionsReflection.amount(got);
            if (transferred < 0 || transferred > amount) {
                return simulate
                        ? StorageOperationResult.failedExtraction(mode(simulate), amount,
                        StorageOperationStatus.INVALID_RESPONSE, List.of(), List.of(),
                        StorageDiagnosticCode.INVALID_NATIVE_RESPONSE)
                        : StorageOperationResult.indeterminateExtraction(amount, List.of(), List.of(),
                        StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
            }
            if (transferred > Integer.MAX_VALUE) {
                return simulate
                        ? StorageOperationResult.failedExtraction(mode(simulate), amount,
                        StorageOperationStatus.UNAVAILABLE, List.of(), List.of(),
                        StorageDiagnosticCode.NATIVE_LIMIT_EXCEEDED)
                        : StorageOperationResult.indeterminateExtraction(amount, List.of(), List.of(),
                        StorageDiagnosticCode.NATIVE_LIMIT_EXCEEDED);
            }
            ItemStack template = transferred <= 0 ? ItemStack.EMPTY
                    : nativeStack(BeyondDimensionsReflection.key(got), key.displayStack());
            if (transferred > 0 && (template.isEmpty() || !key.equals(itemKey(template)))) {
                List<ItemStack> recovery = template.isEmpty() ? List.of() : split(template, transferred);
                return simulate
                        ? StorageOperationResult.failedExtraction(mode(simulate), amount,
                        StorageOperationStatus.INVALID_RESPONSE, List.of(), recovery,
                        StorageDiagnosticCode.INVALID_NATIVE_RESPONSE)
                        : StorageOperationResult.indeterminateExtraction(amount, List.of(), recovery,
                        StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
            }
            List<ItemStack> out = split(template, transferred);
            return StorageOperationResult.extracted(mode(simulate), amount, out);
        } catch (Exception | LinkageError e) {
            return simulate
                    ? StorageOperationResult.failedExtraction(mode(simulate), amount,
                    StorageOperationStatus.FAILED, List.of(), List.of(),
                    StorageDiagnosticCode.BACKEND_EXCEPTION)
                    : StorageOperationResult.indeterminateExtraction(amount, List.of(), List.of(),
                    StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION);
        }
    }

    @Override public StorageOperationResult insert(ServerPlayer player, ItemStack stack, boolean simulate) {
        StorageThreadGuard.requireServerThread(player);
        if (stack.isEmpty()) return StorageOperationResult.inserted(mode(simulate), ItemStack.EMPTY, ItemStack.EMPTY);
        StoragePermissionResult permission = checkPermission(player, StoragePermission.INSERT);
        if (!permission.allowedAccess()) return permissionFailureInsert(mode(simulate), stack, permission);
        Object nativeKey;
        Object storage;
        Method insert;
        try {
            storage = storage();
            nativeKey = InkFluidSupport.isToken(stack)
                    ? BeyondDimensionsReflection.fluidKey(InkFluidSupport.fluid(stack))
                    : BeyondDimensionsReflection.itemKey(stack);
            insert = storage.getClass().getMethod("insert", Class.forName("com.wintercogs.beyonddimensions.api.storage.key.IStackKey"), long.class, boolean.class);
        } catch (Exception | LinkageError e) {
            return StorageOperationResult.failedInsert(mode(simulate), stack,
                    StorageOperationStatus.FAILED, StorageDiagnosticCode.BACKEND_EXCEPTION);
        }
        try {
            Object remainder = insert.invoke(storage, nativeKey, (long) stack.getCount(), simulate);
            long left = BeyondDimensionsReflection.amount(remainder);
            if (left < 0 || left > stack.getCount()) {
                return simulate
                        ? StorageOperationResult.failedInsert(mode(simulate), stack,
                        StorageOperationStatus.INVALID_RESPONSE, StorageDiagnosticCode.INVALID_NATIVE_RESPONSE)
                        : StorageOperationResult.indeterminateInsert(stack,
                        StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
            }
            if (left > 0) {
                ItemStack remainderStack = nativeStack(
                        BeyondDimensionsReflection.key(remainder), stack);
                if (remainderStack.isEmpty() || !itemKey(stack).equals(itemKey(remainderStack))) {
                    return simulate
                            ? StorageOperationResult.failedInsert(mode(simulate), stack,
                            StorageOperationStatus.INVALID_RESPONSE, StorageDiagnosticCode.INVALID_NATIVE_RESPONSE)
                            : StorageOperationResult.indeterminateInsert(stack,
                            StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
                }
                return StorageOperationResult.inserted(mode(simulate), stack,
                        remainderStack.copyWithCount((int) left), (a, b) -> itemKey(a).equals(itemKey(b)));
            }
            return StorageOperationResult.inserted(mode(simulate), stack, ItemStack.EMPTY);
        } catch (Exception | LinkageError e) {
            return simulate
                    ? StorageOperationResult.failedInsert(mode(simulate), stack,
                    StorageOperationStatus.FAILED, StorageDiagnosticCode.BACKEND_EXCEPTION)
                    : StorageOperationResult.indeterminateInsert(stack,
                    StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION);
        }
    }

    private static ItemStack nativeStack(Object nativeKey, ItemStack template) throws Exception {
        return InkFluidSupport.isToken(template)
                ? InkFluidSupport.token(template.getItem(), BeyondDimensionsReflection.fluidStack(nativeKey))
                : BeyondDimensionsReflection.keyStack(nativeKey);
    }

    private static List<ItemStack> split(ItemStack template, long amount) {
        if (template.isEmpty() || amount <= 0) return List.of();
        List<ItemStack> stacks = new ArrayList<>();
        long remaining = amount;
        while (remaining > 0) {
            int count = (int) Math.min(Integer.MAX_VALUE, remaining);
            stacks.add(template.copyWithCount(count));
            remaining -= count;
        }
        return stacks;
    }

    static StorageItemKey storedPayloadKey(StorageSnapshot snapshot, StorageItemKey requested) {
        return snapshot.items().stream()
                .map(StoredItem::key)
                .filter(requested::equals)
                .findFirst()
                .orElse(requested);
    }

    private static StorageSnapshotResult snapshotFailure(StoragePermissionResult permission) {
        return switch (permission.status()) {
            case DENIED -> StorageSnapshotResult.failure(StorageSnapshotStatus.DENIED);
            case UNAVAILABLE -> StorageSnapshotResult.failure(StorageSnapshotStatus.UNAVAILABLE);
            case FAILED -> StorageSnapshotResult.failure(StorageSnapshotStatus.FAILED,
                    permission.diagnosticCode());
            case ALLOWED -> throw new IllegalArgumentException("allowed permission cannot be a failure");
        };
    }

    private static StorageOperationResult permissionFailureExtraction(StorageOperationMode mode,
                                                                      long amount,
                                                                      StoragePermissionResult permission) {
        return StorageOperationResult.failedExtraction(mode, amount,
                switch (permission.status()) {
                    case DENIED -> StorageOperationStatus.DENIED;
                    case UNAVAILABLE -> StorageOperationStatus.UNAVAILABLE;
                    case FAILED -> StorageOperationStatus.FAILED;
                    case ALLOWED -> throw new IllegalArgumentException("allowed permission cannot be a failure");
                }, List.of(), List.of(), permission.diagnosticCode());
    }

    private static StorageOperationResult permissionFailureInsert(StorageOperationMode mode,
                                                                  ItemStack stack,
                                                                  StoragePermissionResult permission) {
        return switch (permission.status()) {
            case DENIED -> StorageOperationResult.failedInsert(mode, stack, StorageOperationStatus.DENIED);
            case UNAVAILABLE -> StorageOperationResult.failedInsert(mode, stack, StorageOperationStatus.UNAVAILABLE);
            case FAILED -> StorageOperationResult.failedInsert(mode, stack,
                    StorageOperationStatus.FAILED, permission.diagnosticCode());
            case ALLOWED -> throw new IllegalArgumentException("allowed permission cannot be a failure");
        };
    }

    private static StorageOperationMode mode(boolean simulate) { return simulate ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM; }
}
