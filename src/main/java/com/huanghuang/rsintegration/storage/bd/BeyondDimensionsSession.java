package com.huanghuang.rsintegration.storage.bd;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.huanghuang.rsintegration.storage.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;

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
    @Override public Set<StorageCapability> capabilities() {
        return Set.of(StorageCapability.ITEM_STORAGE, StorageCapability.FLUID_STORAGE);
    }
    @Override public StorageItemKey itemKey(ItemStack stack) {
        return BeyondDimensionsItemKeys.fromStack(reference.backendId(), stack);
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
            return StorageSnapshotResult.success(readItemSnapshot(reference.backendId(), list, itemTypes,
                    (stack, failure) -> warnSkippedItem(player, stack, failure)));
        } catch (Exception | LinkageError e) {
            com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.warn(
                    "[RSI-Storage] BD item snapshot failed player={} network={} cause={}",
                    player.getGameProfile().getName(), reference.networkId(), e.toString());
            return StorageSnapshotResult.failure(StorageSnapshotStatus.FAILED,
                    StorageDiagnosticCode.BACKEND_EXCEPTION);
        }
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
        com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.warn(
                "[RSI-Storage] BD snapshot skipped unsupported item player={} network={} item={} cause={}; "
                        + "native item unchanged, other items remain available (once per minute per network/item)",
                player.getGameProfile().getName(), reference.networkId(), itemId, failure.toString());
    }

    @Override
    public long countDerivedContainer(ServerPlayer player, ItemStack filledContainer) {
        StorageThreadGuard.requireServerThread(player);
        if (filledContainer.isEmpty()
                || (filledContainer.getItem() != net.minecraft.world.item.Items.WATER_BUCKET
                && filledContainer.getItem() != net.minecraft.world.item.Items.LAVA_BUCKET)) {
            return 0L;
        }
        StoragePermissionResult permission = checkPermission(player, StoragePermission.VIEW);
        if (!permission.allowedAccess()) return 0L;
        try {
            Object nativeStorage = storage();
            Object values = nativeStorage.getClass().getMethod("getStorage").invoke(nativeStorage);
            long emptyBuckets = 0L;
            long ironForBuckets = 0L;
            long fluidMilliBuckets = 0L;
            ItemStack empty = new ItemStack(net.minecraft.world.item.Items.BUCKET);
            Fluid wanted = filledContainer.getItem() == net.minecraft.world.item.Items.WATER_BUCKET
                    ? Fluids.WATER : Fluids.LAVA;
            if (values instanceof Iterable<?> entries) {
                for (Object value : entries) {
                    Object key = BeyondDimensionsReflection.key(value);
                    long amount = Math.max(0L, BeyondDimensionsReflection.amount(value));
                    if (amount <= 0L) continue;
                    if (BeyondDimensionsReflection.isItemKey(key)) {
                        ItemStack stack = BeyondDimensionsReflection.keyStack(key);
                        if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, empty)) {
                            emptyBuckets = saturatedAdd(emptyBuckets, amount);
                        } else if (!stack.isEmpty() && stack.is(net.minecraft.world.item.Items.IRON_INGOT)) {
                            ironForBuckets = saturatedAdd(ironForBuckets, amount);
                        }
                    } else {
                        // Do not rely solely on getStackClass(): BD revisions
                        // have returned the API interface class there while
                        // still exposing a normal FluidStack read-only value.
                        FluidStack fluidStack;
                        try {
                            fluidStack = BeyondDimensionsReflection.fluidStack(key);
                        } catch (ReflectiveOperationException | ClassCastException ignored) {
                            fluidStack = null;
                        }
                        if (fluidStack != null && !fluidStack.isEmpty()
                                && net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(fluidStack.getFluid())
                                != null
                                && net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(fluidStack.getFluid())
                                .equals(net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(wanted))) {
                            // BD 0.7.x stores fluid entries as milliBuckets;
                            // tolerate older key-count representations where
                            // the entry count is the number of key stacks.
                            long perKey = Math.max(1, fluidStack.getAmount());
                            long contribution = amount >= perKey ? amount : saturatedMultiply(amount, perKey);
                            fluidMilliBuckets = saturatedAdd(fluidMilliBuckets, contribution);
                        }
                    }
                }
            }
            long derived = Math.min(saturatedAdd(emptyBuckets, ironForBuckets / 3L), fluidMilliBuckets / 1000L);
            com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.debug(
                    "[RSI-Storage] BD derived container={} emptyBuckets={} ironForBuckets={} fluidMb={} derived={}",
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(filledContainer.getItem()),
                    emptyBuckets, ironForBuckets / 3L, fluidMilliBuckets, derived);
            return derived;
        } catch (Exception | LinkageError e) {
            com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.debug(
                    "[RSI-Storage] BD derived container count failed network={}",
                    reference.networkId(), e);
            return 0L;
        }
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatedMultiply(long left, long right) {
        return left == 0 || right == 0 || left > Long.MAX_VALUE / right
                ? (left == 0 || right == 0 ? 0 : Long.MAX_VALUE)
                : left * right;
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
        return com.huanghuang.rsintegration.crafting.IngredientMatcher.itemTypesForMatching(ingredient);
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

    @Override
    public StorageOperationResult extractContainerFluid(ServerPlayer player, ItemStack emptyContainer,
                                                         ItemStack filledContainer, long amount,
                                                         boolean simulate) {
        StorageThreadGuard.requireServerThread(player);
        if (amount < 0 || emptyContainer.isEmpty() || filledContainer.isEmpty()) {
            return StorageOperationResult.failedExtraction(mode(simulate), amount,
                    StorageOperationStatus.INVALID_REQUEST, List.of(), List.of());
        }
        if (amount == 0) return StorageOperationResult.extracted(mode(simulate), 0, List.of());
        boolean waterBucket = filledContainer.is(net.minecraft.world.item.Items.WATER_BUCKET);
        boolean lavaBucket = filledContainer.is(net.minecraft.world.item.Items.LAVA_BUCKET);
        if (!waterBucket && !lavaBucket) {
            return StorageOperationResult.failedExtraction(mode(simulate), amount,
                    StorageOperationStatus.INVALID_REQUEST, List.of(), List.of());
        }
        Fluid fluid = BuiltInRegistries.FLUID.get(new net.minecraft.resources.ResourceLocation("minecraft",
                waterBucket ? "water" : "lava"));
        if (fluid == null || fluid == Fluids.EMPTY) {
            return StorageOperationResult.failedExtraction(mode(simulate), amount,
                    StorageOperationStatus.INVALID_REQUEST, List.of(), List.of());
        }
        StoragePermissionResult permission = checkPermission(player, StoragePermission.EXTRACT);
        if (!permission.allowedAccess()) return permissionFailureExtraction(mode(simulate), amount, permission);
        try {
            // A filled bucket may be stored as a normal item in BD.  Prefer
            // that representation before attempting the derived
            // empty-bucket + fluid conversion.  The reservation path calls
            // this method in simulate mode and the commit path calls it in
            // perform mode, so both phases make the same choice.
            StorageItemKey filledKey = itemKey(filledContainer);
            StorageOperationResult directProbe = extractNative(
                    player, filledKey, amount, true, false);
            if (directProbe.status() == StorageOperationStatus.SUCCESS) {
                if (simulate) return directProbe;
                // Probe first so a partially stocked item can still use the
                // derived conversion without consuming that partial amount.
                StorageOperationResult direct = extractNative(
                        player, filledKey, amount, false, false);
                if (direct.status() != StorageOperationStatus.NOT_FOUND) return direct;
            }

            Object nativeStorage = storage();
            Class<?> keyType = Class.forName("com.wintercogs.beyonddimensions.api.storage.key.IStackKey");
            var extract = nativeStorage.getClass().getMethod("extract", keyType, long.class, boolean.class, boolean.class);
            Object bucketKey = BeyondDimensionsReflection.itemKey(emptyContainer);
            Object bucket = extract.invoke(nativeStorage, bucketKey, amount, simulate, false);
            long bucketAmount = BeyondDimensionsReflection.amount(bucket);
            long ironBuckets = amount - bucketAmount;
            Object ironKey = null;
            long ironExtracted = 0L;
            if (ironBuckets > 0) {
                ironKey = BeyondDimensionsReflection.itemKey(new ItemStack(net.minecraft.world.item.Items.IRON_INGOT));
                Object iron = extract.invoke(nativeStorage, ironKey, 3L * ironBuckets, simulate, false);
                ironExtracted = BeyondDimensionsReflection.amount(iron);
                if (ironExtracted < 3L * ironBuckets) {
                    if (!simulate && bucketAmount > 0) {
                        nativeStorage.getClass().getMethod("insert", keyType, long.class, boolean.class)
                                .invoke(nativeStorage, bucketKey, bucketAmount, false);
                    }
                    return StorageOperationResult.extracted(mode(simulate), amount, List.of());
                }
            }
            Object fluidKey = BeyondDimensionsReflection.fluidKey(fluid, 1000L);
            Object fluidResult = extract.invoke(nativeStorage, fluidKey, 1000L * amount, simulate, false);
            long fluidAmount = BeyondDimensionsReflection.amount(fluidResult);
            if (fluidAmount != 1000L * amount) {
                if (!simulate) {
                    Object remainder = nativeStorage.getClass().getMethod("insert", keyType, long.class, boolean.class)
                            .invoke(nativeStorage, bucketKey, bucketAmount, false);
                    if (BeyondDimensionsReflection.amount(remainder) > 0) {
                        com.huanghuang.rsintegration.RSIntegrationMod.LOGGER.error("[RSI-Storage] BD container conversion rollback left buckets unrecovered");
                    }
                    if (ironExtracted > 0 && ironKey != null) {
                        nativeStorage.getClass().getMethod("insert", keyType, long.class, boolean.class)
                                .invoke(nativeStorage, ironKey, ironExtracted, false);
                    }
                }
                return StorageOperationResult.extracted(mode(simulate), amount, List.of());
            }
            return StorageOperationResult.extracted(mode(simulate), amount,
                    List.of(filledContainer.copyWithCount(Math.toIntExact(amount))));
        } catch (Exception | LinkageError e) {
            return simulate
                    ? StorageOperationResult.failedExtraction(mode(simulate), amount,
                    StorageOperationStatus.FAILED, List.of(), List.of(), StorageDiagnosticCode.BACKEND_EXCEPTION)
                    : StorageOperationResult.indeterminateExtraction(amount, List.of(), List.of(),
                    StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION);
        }
    }

    private StorageOperationResult extractNative(ServerPlayer player, StorageItemKey key, long amount, boolean simulate, boolean fuzzy) {
        StorageThreadGuard.requireServerThread(player);
        if (amount < 0) throw new IllegalArgumentException("amount must not be negative");
        if (!reference.backendId().equals(key.backendId())) return StorageOperationResult.failedExtraction(mode(simulate), amount, StorageOperationStatus.INVALID_REQUEST, List.of(), List.of());
        StoragePermissionResult permission = checkPermission(player, StoragePermission.EXTRACT);
        if (!permission.allowedAccess()) return permissionFailureExtraction(mode(simulate), amount, permission);
        Object nativeKey;
        Object nativeStorage;
        java.lang.reflect.Method extract;
        try {
            nativeStorage = storage();
            StorageItemKey storedKey = snapshotForExtraction(player,
                    Set.of(BuiltInRegistries.ITEM.get(key.itemType()))).snapshot()
                    .map(snapshot -> storedPayloadKey(snapshot, key))
                    .orElse(key);
            nativeKey = BeyondDimensionsReflection.itemKey(ItemStack.of(storedKey.backendPayload()));
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
                    : BeyondDimensionsReflection.keyStack(BeyondDimensionsReflection.key(got));
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
        java.lang.reflect.Method insert;
        try {
            storage = storage();
            nativeKey = BeyondDimensionsReflection.itemKey(stack);
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
                ItemStack remainderStack = BeyondDimensionsReflection.keyStack(
                        BeyondDimensionsReflection.key(remainder));
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
