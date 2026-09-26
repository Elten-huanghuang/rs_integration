package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageInsertObserver;
import com.huanghuang.rsintegration.storage.StorageItemChangeListener;
import com.huanghuang.rsintegration.storage.StorageItemSubscription;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StoragePermissionResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StorageSnapshotStatus;
import com.huanghuang.rsintegration.storage.StorageThreadGuard;
import com.huanghuang.rsintegration.storage.StoredItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import java.util.Set;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** StorageSession backed by one RS network. Native RS types do not escape the driver. */
final class RefinedStorageSession implements StorageSession {
    private final RefinedStorageDriver driver;
    private final StorageReference reference;
    private final StorageInsertObserver insertObserver;

    RefinedStorageSession(RefinedStorageDriver driver, StorageReference reference,
                          StorageInsertObserver insertObserver) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.reference = Objects.requireNonNull(reference, "reference");
        this.insertObserver = Objects.requireNonNull(insertObserver, "insertObserver");
    }

    @Override
    public StorageReference reference() { return reference; }

    @Override
    public StorageItemKey itemKey(ItemStack stack) {
        return RefinedStorageItemKeys.fromStack(stack);
    }

    @Override
    public Optional<StorageItemSubscription> subscribeItemChanges(StorageItemChangeListener listener) {
        return driver.subscribeItemChanges(listener);
    }

    @Override
    public StorageSnapshotResult snapshotItems(ServerPlayer player) {
        return snapshotItems(player, null);
    }

    @Override
    public StorageSnapshotResult snapshotItems(ServerPlayer player,
            Set<Item> itemTypes) {
        StorageThreadGuard.requireServerThread(player);
        StoragePermissionResult permission = checkPermissionInternal(player, StoragePermission.VIEW);
        if (!permission.allowedAccess()) {
            return StorageSnapshotResult.failure(toSnapshotStatus(permission), permission.diagnosticCode());
        }
        try {
            return RefinedStorageSnapshotMapper.map(driver.snapshotItems(itemTypes));
        } catch (RefinedStorageUnavailableException e) {
            return StorageSnapshotResult.failure(StorageSnapshotStatus.UNAVAILABLE);
        } catch (RuntimeException | LinkageError e) {
            return StorageSnapshotResult.failure(StorageSnapshotStatus.FAILED,
                    StorageDiagnosticCode.BACKEND_EXCEPTION);
        }
    }

    @Override
    public StoragePermissionResult checkPermission(ServerPlayer player, StoragePermission permission) {
        StorageThreadGuard.requireServerThread(player);
        return checkPermissionInternal(player, permission);
    }

    private StoragePermissionResult checkPermissionInternal(ServerPlayer player, StoragePermission permission) {
        Objects.requireNonNull(permission, "permission");
        try {
            if (!driver.isAvailable()) return StoragePermissionResult.unavailable();
            return driver.hasPermission(player, permission)
                    ? StoragePermissionResult.allowed() : StoragePermissionResult.denied();
        } catch (RefinedStorageUnavailableException e) {
            return StoragePermissionResult.unavailable();
        } catch (RuntimeException | LinkageError e) {
            return StoragePermissionResult.failed(StorageDiagnosticCode.PERMISSION_CHECK_FAILED);
        }
    }

    @Override
    public StorageOperationResult extractExact(ServerPlayer player, StorageItemKey key,
                                               long amount, boolean simulate) {
        StorageThreadGuard.requireServerThread(player);
        Objects.requireNonNull(key, "key");
        validateAmount(amount);
        if (!RefinedStorageIds.BACKEND.equals(key.backendId())) {
            return StorageOperationResult.failedExtraction(mode(simulate), amount,
                    StorageOperationStatus.INVALID_REQUEST, List.of(), List.of());
        }
        if (amount == 0) return StorageOperationResult.extracted(
                mode(simulate), 0, List.of());
        StoragePermissionResult permission = checkPermissionInternal(player, StoragePermission.EXTRACT);
        if (!permission.allowedAccess()) return failedPermissionExtraction(
                amount, permission, mode(simulate));
        ItemStack template = ItemStack.of(key.backendPayload());
        if (template.isEmpty()) return StorageOperationResult.failedExtraction(
                mode(simulate), amount,
                StorageOperationStatus.INVALID_REQUEST, List.of(), List.of());
        // The native extraction result is authoritative and is already checked
        // for amount and exact identity by the executor. Pre-scanning the whole
        // network here only creates a stale upper bound and forced one complete
        // snapshot rebuild per committed exact ledger entry.
        StorageOperationResult result = RefinedStorageOperationExecutor.extract(
                driver, key, template, amount, amount, simulate);
        return result;
    }

    @Override
    public StorageOperationResult extractMatching(ServerPlayer player, Ingredient ingredient,
                                                  long amount, boolean simulate) {
        StorageThreadGuard.requireServerThread(player);
        Objects.requireNonNull(ingredient, "ingredient");
        validateAmount(amount);
        if (amount == 0) return StorageOperationResult.extracted(
                mode(simulate), 0, List.of());
        StoragePermissionResult permission = checkPermissionInternal(player, StoragePermission.EXTRACT);
        if (!permission.allowedAccess()) return failedPermissionExtraction(
                amount, permission, mode(simulate));
        StorageSnapshotResult snapshot;
        try {
            snapshot = snapshotItems(player, IngredientMatcher
                    .itemTypesForMatching(ingredient));
        } catch (RuntimeException | LinkageError failure) {
            return StorageOperationResult.failedExtraction(mode(simulate), amount,
                    StorageOperationStatus.FAILED, List.of(), List.of(),
                    StorageDiagnosticCode.INGREDIENT_MATCH_FAILED);
        }
        if (!snapshot.successful()) return StorageOperationResult.failedExtraction(
                mode(simulate), amount, toOperationStatus(snapshot.status()), List.of(), List.of(),
                snapshot.diagnosticCode());

        StorageSnapshot.MatchResult match = snapshot.snapshot().orElseThrow().match(ingredient);
        if (match.status() == StorageSnapshot.MatchStatus.EMPTY_INGREDIENT) {
            return StorageOperationResult.failedExtraction(mode(simulate), amount,
                    StorageOperationStatus.INVALID_REQUEST, List.of(), List.of());
        }
        if (!match.successful()) return StorageOperationResult.failedExtraction(
                mode(simulate), amount, StorageOperationStatus.FAILED,
                List.of(), List.of(), match.diagnosticCode());

        long remaining = amount;
        List<ItemStack> extracted = new ArrayList<>();
        for (StoredItem stored : match.items()) {
            if (remaining <= 0) break;
            long target = Math.min(remaining, stored.amount());
            StorageOperationResult result = RefinedStorageOperationExecutor.extract(driver,
                    stored.key(), stored.stack(), remaining, target, simulate);
            extracted.addAll(result.extractedStacks());
            if (result.transferredAmount().isEmpty()) {
                return StorageOperationResult.indeterminateExtraction(amount, extracted,
                        result.recoveryStacks(), result.diagnosticCode());
            }
            if (result.status() != StorageOperationStatus.SUCCESS
                    && result.status() != StorageOperationStatus.PARTIAL
                    && result.status() != StorageOperationStatus.NOT_FOUND) {
                return StorageOperationResult.failedExtraction(mode(simulate), amount,
                        result.status(), extracted,
                        result.recoveryStacks(), result.diagnosticCode());
            }
            remaining -= result.transferredAmount().orElseThrow();
        }
        StorageOperationResult result = StorageOperationResult.extracted(mode(simulate), amount, extracted);
        return result;
    }

    @Override
    public StorageOperationResult insert(ServerPlayer player, ItemStack stack, boolean simulate) {
        StorageThreadGuard.requireServerThread(player);
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) return StorageOperationResult.inserted(
                mode(simulate), ItemStack.EMPTY, ItemStack.EMPTY);
        StoragePermissionResult permission = checkPermissionInternal(player, StoragePermission.INSERT);
        if (!permission.allowedAccess()) {
            return StorageOperationResult.failedInsert(mode(simulate), stack,
                    toOperationStatus(permission),
                    permission.diagnosticCode());
        }
        ItemStack input = stack.copy();
        StorageOperationResult result = RefinedStorageOperationExecutor.insert(driver, input, simulate,
                accepted -> driver.recordInsertion(player, accepted),
                accepted -> insertObserver.beforePerform(player, reference, accepted));
        return result;
    }

    private static StorageOperationResult failedPermissionExtraction(long amount,
                                                                    StoragePermissionResult permission,
                                                                    StorageOperationMode mode) {
        return StorageOperationResult.failedExtraction(mode, amount, toOperationStatus(permission),
                List.of(), List.of(), permission.diagnosticCode());
    }

    private static StorageOperationStatus toOperationStatus(StoragePermissionResult permission) {
        return switch (permission.status()) {
            case ALLOWED -> StorageOperationStatus.SUCCESS;
            case DENIED -> StorageOperationStatus.DENIED;
            case UNAVAILABLE -> StorageOperationStatus.UNAVAILABLE;
            case FAILED -> StorageOperationStatus.FAILED;
        };
    }

    private static StorageSnapshotStatus toSnapshotStatus(StoragePermissionResult permission) {
        return switch (permission.status()) {
            case ALLOWED -> StorageSnapshotStatus.SUCCESS;
            case DENIED -> StorageSnapshotStatus.DENIED;
            case UNAVAILABLE -> StorageSnapshotStatus.UNAVAILABLE;
            case FAILED -> StorageSnapshotStatus.FAILED;
        };
    }

    private static StorageOperationStatus toOperationStatus(StorageSnapshotStatus status) {
        return switch (status) {
            case SUCCESS -> StorageOperationStatus.SUCCESS;
            case DENIED -> StorageOperationStatus.DENIED;
            case UNAVAILABLE -> StorageOperationStatus.UNAVAILABLE;
            case INVALID_RESPONSE -> StorageOperationStatus.INVALID_RESPONSE;
            case FAILED -> StorageOperationStatus.FAILED;
        };
    }

    private static void validateAmount(long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount must not be negative");
    }

    private static StorageOperationMode mode(boolean simulate) {
        return simulate ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM;
    }
}
