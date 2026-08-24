package com.huanghuang.rsintegration.storage.bd;

import com.huanghuang.rsintegration.storage.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BeyondDimensionsSession implements StorageSession {
    private final Object network;
    private final StorageReference reference;

    BeyondDimensionsSession(Object network, StorageReference reference) {
        this.network = Objects.requireNonNull(network, "network");
        this.reference = Objects.requireNonNull(reference, "reference");
    }
    @Override public StorageReference reference() { return reference; }
    @Override public StorageItemKey itemKey(ItemStack stack) {
        return StorageItemKey.fromItemStack(reference.backendId(), stack);
    }

    private Object storage() throws Exception {
        return network.getClass().getMethod("getUnifiedStorage").invoke(network);
    }

    @Override public StorageSnapshotResult snapshotItems(ServerPlayer player) {
        StorageThreadGuard.requireServerThread(player);
        StoragePermissionResult permission = checkPermission(player, StoragePermission.VIEW);
        if (!permission.allowedAccess()) return snapshotFailure(permission);
        try {
            List<StoredItem> items = new ArrayList<>();
            Object list = storage().getClass().getMethod("getStorage").invoke(storage());
            if (list instanceof Iterable<?> values) for (Object value : values) {
                Object key = BeyondDimensionsReflection.key(value);
                ItemStack stack = BeyondDimensionsReflection.keyStack(key);
                long amount = BeyondDimensionsReflection.amount(value);
                if (!stack.isEmpty() && amount > 0) items.add(new StoredItem(itemKey(stack), amount));
            }
            return StorageSnapshotResult.success(new StorageSnapshot(reference.backendId(), items));
        } catch (Exception | LinkageError e) {
            return StorageSnapshotResult.failure(StorageSnapshotStatus.FAILED,
                    StorageDiagnosticCode.BACKEND_EXCEPTION);
        }
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
        if (amount < 0) throw new IllegalArgumentException("amount must not be negative");
        if (amount == 0) return StorageOperationResult.extracted(mode(simulate), 0, List.of());
        StorageSnapshotResult snapshot = snapshotItems(player);
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
            nativeKey = BeyondDimensionsReflection.itemKey(ItemStack.of(key.backendPayload()));
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
