package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Pure operation sequencing around a replaceable RS driver. */
final class RefinedStorageOperationExecutor {
    private RefinedStorageOperationExecutor() {}

    static StorageOperationResult insert(RefinedStorageDriver driver, ItemStack input,
                                         boolean simulate, Consumer<ItemStack> recordAccepted) {
        return insert(driver, input, simulate, recordAccepted, accepted -> { });
    }

    static StorageOperationResult insert(RefinedStorageDriver driver, ItemStack input,
                                         boolean simulate, Consumer<ItemStack> recordAccepted,
                                         Consumer<ItemStack> beforePerform) {
        if (simulate) {
            try {
                ItemStack remainder = driver.insert(input.copy(), true);
                if (remainder == null) return invalidSimulatedInsert(
                        input, StorageOperationMode.SIMULATE);
                return StorageOperationResult.inserted(
                        StorageOperationMode.SIMULATE, input, remainder);
            } catch (IllegalArgumentException e) {
                return StorageOperationResult.failedInsert(StorageOperationMode.SIMULATE,
                        input, StorageOperationStatus.INVALID_RESPONSE,
                        StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
            } catch (RuntimeException | LinkageError e) {
                return StorageOperationResult.failedInsert(StorageOperationMode.SIMULATE,
                        input, StorageOperationStatus.FAILED,
                        StorageDiagnosticCode.BACKEND_EXCEPTION);
            }
        }

        StorageOperationResult simulated;
        try {
            ItemStack remainder = driver.insert(input.copy(), true);
            if (remainder == null) return invalidSimulatedInsert(
                    input, StorageOperationMode.PERFORM);
            simulated = StorageOperationResult.inserted(
                    StorageOperationMode.SIMULATE, input, remainder);
        } catch (IllegalArgumentException e) {
            return StorageOperationResult.failedInsert(input, StorageOperationStatus.INVALID_RESPONSE,
                    StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
        } catch (RuntimeException | LinkageError e) {
            return StorageOperationResult.failedInsert(input, StorageOperationStatus.FAILED,
                    StorageDiagnosticCode.BACKEND_EXCEPTION);
        }
        long simulatedTransfer = simulated.transferredAmount().orElseThrow();
        ItemStack acceptedEstimate = simulatedTransfer > 0
                ? input.copyWithCount(Math.toIntExact(simulatedTransfer)) : ItemStack.EMPTY;
        if (simulatedTransfer > 0) {
            try {
                recordAccepted.accept(acceptedEstimate.copy());
            } catch (RuntimeException | LinkageError e) {
                return StorageOperationResult.failedInsert(input, StorageOperationStatus.FAILED,
                        StorageDiagnosticCode.CHANGE_TRACKING_FAILED);
            }
        }
        try {
            beforePerform.accept(acceptedEstimate.copy());
        } catch (RuntimeException | LinkageError e) {
            return StorageOperationResult.failedInsert(input, StorageOperationStatus.FAILED,
                    StorageDiagnosticCode.INSERT_OBSERVER_FAILED);
        }
        try {
            ItemStack remainder = driver.insert(input.copy(), false);
            if (remainder == null) {
                return StorageOperationResult.indeterminateInsert(input,
                        StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
            }
            return StorageOperationResult.inserted(input, remainder);
        } catch (IllegalArgumentException e) {
            return StorageOperationResult.indeterminateInsert(input,
                    StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
        } catch (RuntimeException | LinkageError e) {
            return StorageOperationResult.indeterminateInsert(input,
                    StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION);
        }
    }

    private static StorageOperationResult invalidSimulatedInsert(ItemStack input,
                                                                 StorageOperationMode resultMode) {
        return StorageOperationResult.failedInsert(resultMode, input,
                StorageOperationStatus.INVALID_RESPONSE,
                StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
    }

    static StorageOperationResult extract(RefinedStorageDriver driver, StorageItemKey expectedKey,
                                          ItemStack template, long requestedAmount, long target,
                                          boolean simulate) {
        List<ItemStack> extracted = new ArrayList<>();
        List<ItemStack> recovery = new ArrayList<>();
        StorageOperationMode mode = simulate
                ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM;
        if (simulate && target > Integer.MAX_VALUE) {
            return StorageOperationResult.failedExtraction(mode, requestedAmount,
                    StorageOperationStatus.UNAVAILABLE, extracted, recovery,
                    StorageDiagnosticCode.NATIVE_LIMIT_EXCEEDED);
        }
        long remaining = target;
        while (remaining > 0) {
            int request = (int) Math.min(remaining, Integer.MAX_VALUE);
            ItemStack result;
            try {
                result = driver.extract(template.copyWithCount(1), request, simulate);
            } catch (RuntimeException | LinkageError e) {
                return simulate
                        ? StorageOperationResult.failedExtraction(mode, requestedAmount,
                                StorageOperationStatus.FAILED, extracted, recovery,
                                StorageDiagnosticCode.BACKEND_EXCEPTION)
                        : StorageOperationResult.indeterminateExtraction(requestedAmount,
                                extracted, recovery,
                                StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION);
            }
            if (result == null) {
                return simulate
                        ? StorageOperationResult.failedExtraction(mode, requestedAmount,
                                StorageOperationStatus.INVALID_RESPONSE, extracted, recovery,
                                StorageDiagnosticCode.INVALID_NATIVE_RESPONSE)
                        : StorageOperationResult.indeterminateExtraction(requestedAmount,
                                extracted, recovery, StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
            }
            if (result.isEmpty()) break;
            StorageItemKey returnedKey;
            try {
                returnedKey = StorageItemKey.fromItemStack(expectedKey.backendId(), result);
            } catch (IllegalArgumentException e) {
                if (!simulate) recovery.add(result.copy());
                return simulate
                        ? StorageOperationResult.failedExtraction(mode, requestedAmount,
                                StorageOperationStatus.INVALID_RESPONSE, extracted, recovery,
                                StorageDiagnosticCode.INVALID_NATIVE_RESPONSE)
                        : StorageOperationResult.indeterminateExtraction(requestedAmount,
                                extracted, recovery, StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
            }
            if (!expectedKey.equals(returnedKey)) {
                if (!simulate && !recoverWrongIdentity(driver, result, recovery)) {
                    return StorageOperationResult.indeterminateExtraction(requestedAmount,
                            extracted, recovery,
                            StorageDiagnosticCode.NATIVE_OPERATION_FAILED_AFTER_MUTATION);
                }
                return StorageOperationResult.failedExtraction(mode, requestedAmount,
                        StorageOperationStatus.INVALID_RESPONSE, extracted, recovery,
                        StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
            }
            if (result.getCount() > request) {
                if (!simulate) recovery.add(result.copy());
                return simulate
                        ? StorageOperationResult.failedExtraction(mode, requestedAmount,
                                StorageOperationStatus.INVALID_RESPONSE, extracted, recovery,
                                StorageDiagnosticCode.INVALID_NATIVE_RESPONSE)
                        : StorageOperationResult.indeterminateExtraction(requestedAmount,
                                extracted, recovery, StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
            }
            extracted.add(result.copy());
            remaining -= result.getCount();
            if (result.getCount() < request || simulate) break;
        }
        return StorageOperationResult.extracted(mode, requestedAmount, extracted);
    }

    private static boolean recoverWrongIdentity(RefinedStorageDriver driver, ItemStack result,
                                                List<ItemStack> recovery) {
        try {
            ItemStack remainder = driver.insert(result.copy(), false);
            if (remainder == null) return false;
            if (!remainder.isEmpty()) recovery.add(remainder.copy());
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
