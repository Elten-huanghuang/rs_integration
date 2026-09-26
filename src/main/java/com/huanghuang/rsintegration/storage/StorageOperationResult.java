package com.huanghuang.rsintegration.storage;
import java.lang.reflect.Field;

import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BiPredicate;

/** Immutable result that separates valid transfers from items requiring recovery. */
public final class StorageOperationResult {
    public enum Kind { INSERT, EXTRACT }

    private final Kind kind;
    private final StorageOperationMode mode;
    private final StorageOperationStatus status;
    private final long requestedAmount;
    private final long transferredAmount;
    private final boolean transferKnown;
    private final List<ItemStack> extractedStacks;
    private final List<ItemStack> recoveryStacks;
    private final ItemStack remainder;
    private final boolean remainderKnown;
    private final StorageDiagnosticCode diagnosticCode;

    private StorageOperationResult(Kind kind, StorageOperationMode mode,
                                   StorageOperationStatus status,
                                   long requestedAmount, long transferredAmount,
                                   List<ItemStack> extractedStacks, List<ItemStack> recoveryStacks,
                                   ItemStack remainder, boolean transferKnown,
                                   boolean remainderKnown, StorageDiagnosticCode diagnosticCode) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.status = Objects.requireNonNull(status, "status");
        this.requestedAmount = requestedAmount;
        this.transferredAmount = transferredAmount;
        this.transferKnown = transferKnown;
        this.extractedStacks = copyStacks(extractedStacks);
        this.recoveryStacks = copyStacks(recoveryStacks);
        this.remainder = copyOrEmpty(remainder);
        this.remainderKnown = remainderKnown;
        this.diagnosticCode = Objects.requireNonNull(diagnosticCode, "diagnosticCode");
    }

    public static StorageOperationResult inserted(StorageOperationMode mode,
                                                  ItemStack input, ItemStack remainder) {
        return inserted(mode, input, remainder, StorageOperationResult::sameSerializedIdentity);
    }

    /** Uses the backend's authoritative identity rule to validate a non-empty remainder. */
    public static StorageOperationResult inserted(StorageOperationMode mode,
                                                  ItemStack input, ItemStack remainder,
                                                  BiPredicate<ItemStack, ItemStack> sameIdentity) {
        Objects.requireNonNull(mode, "mode");
        validateInsert(input, remainder, sameIdentity);
        long requested = input.getCount();
        long transferred = requested - remainder.getCount();
        StorageOperationStatus status = requested == 0 || transferred == requested
                ? StorageOperationStatus.SUCCESS
                : transferred == 0 ? StorageOperationStatus.REJECTED : StorageOperationStatus.PARTIAL;
        return new StorageOperationResult(Kind.INSERT, mode, status, requested, transferred,
                List.of(), List.of(), remainder, true, true, StorageDiagnosticCode.NONE);
    }

    public static StorageOperationResult failedInsert(StorageOperationMode mode, ItemStack input,
                                                      StorageOperationStatus status) {
        return failedInsert(mode, input, status, StorageDiagnosticCode.NONE);
    }

    public static StorageOperationResult failedInsert(StorageOperationMode mode, ItemStack input,
                                                      StorageOperationStatus status,
                                                      StorageDiagnosticCode diagnosticCode) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(input, "input");
        requireFailure(status);
        requireFailureDiagnostic(status, diagnosticCode);
        return new StorageOperationResult(Kind.INSERT, mode, status, input.getCount(), 0,
                List.of(), List.of(), input, true, true, diagnosticCode);
    }

    public static StorageOperationResult indeterminateInsert(ItemStack input,
                                                             StorageDiagnosticCode diagnosticCode) {
        Objects.requireNonNull(input, "input");
        requireDiagnostic(diagnosticCode);
        return new StorageOperationResult(Kind.INSERT, StorageOperationMode.PERFORM,
                StorageOperationStatus.INDETERMINATE,
                input.getCount(), 0, List.of(), List.of(), ItemStack.EMPTY,
                false, false, diagnosticCode);
    }

    public static StorageOperationResult extracted(StorageOperationMode mode, long requestedAmount,
                                                   List<ItemStack> extractedStacks) {
        Objects.requireNonNull(mode, "mode");
        List<ItemStack> copied = validatedExtracted(requestedAmount, extractedStacks);
        long transferred = stackCount(copied);
        StorageOperationStatus status = requestedAmount == 0 || transferred == requestedAmount
                ? StorageOperationStatus.SUCCESS
                : transferred == 0 ? StorageOperationStatus.NOT_FOUND : StorageOperationStatus.PARTIAL;
        return new StorageOperationResult(Kind.EXTRACT, mode, status, requestedAmount, transferred,
                copied, List.of(), ItemStack.EMPTY, true, false, StorageDiagnosticCode.NONE);
    }

    public static StorageOperationResult failedExtraction(StorageOperationMode mode,
                                                          long requestedAmount,
                                                          StorageOperationStatus status,
                                                          List<ItemStack> validExtracted,
                                                          List<ItemStack> recoveryStacks) {
        return failedExtraction(mode, requestedAmount, status, validExtracted, recoveryStacks,
                StorageDiagnosticCode.NONE);
    }

    public static StorageOperationResult failedExtraction(StorageOperationMode mode,
                                                          long requestedAmount,
                                                          StorageOperationStatus status,
                                                          List<ItemStack> validExtracted,
                                                          List<ItemStack> recoveryStacks,
                                                          StorageDiagnosticCode diagnosticCode) {
        Objects.requireNonNull(mode, "mode");
        requireFailure(status);
        requireFailureDiagnostic(status, diagnosticCode);
        List<ItemStack> valid = validatedExtracted(requestedAmount, validExtracted);
        return new StorageOperationResult(Kind.EXTRACT, mode, status, requestedAmount, stackCount(valid),
                valid, recoveryStacks, ItemStack.EMPTY, true, false, diagnosticCode);
    }

    public static StorageOperationResult indeterminateExtraction(long requestedAmount,
                                                                 List<ItemStack> confirmedExtracted,
                                                                 List<ItemStack> recoveryStacks,
                                                                 StorageDiagnosticCode diagnosticCode) {
        List<ItemStack> confirmed = validatedExtracted(requestedAmount, confirmedExtracted);
        requireDiagnostic(diagnosticCode);
        return new StorageOperationResult(Kind.EXTRACT, StorageOperationMode.PERFORM,
                StorageOperationStatus.INDETERMINATE,
                requestedAmount, 0, confirmed, recoveryStacks, ItemStack.EMPTY,
                false, false, diagnosticCode);
    }

    public Kind kind() { return kind; }
    public StorageOperationMode mode() { return mode; }
    public StorageOperationStatus status() { return status; }
    public long requestedAmount() { return requestedAmount; }
    public OptionalLong transferredAmount() {
        return transferKnown ? OptionalLong.of(transferredAmount) : OptionalLong.empty();
    }
    public boolean complete() { return status == StorageOperationStatus.SUCCESS; }
    public List<ItemStack> extractedStacks() {
        requireKind(Kind.EXTRACT, "extracted stacks");
        return copyStacks(extractedStacks);
    }
    public List<ItemStack> recoveryStacks() {
        requireKind(Kind.EXTRACT, "recovery stacks");
        return copyStacks(recoveryStacks);
    }
    public Optional<ItemStack> remainder() {
        requireKind(Kind.INSERT, "insert remainder");
        return remainderKnown ? Optional.of(copyOrEmpty(remainder)) : Optional.empty();
    }
    public StorageDiagnosticCode diagnosticCode() { return diagnosticCode; }

    private static void validateInsert(ItemStack input, ItemStack remainder,
                                       BiPredicate<ItemStack, ItemStack> sameIdentity) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(remainder, "remainder");
        Objects.requireNonNull(sameIdentity, "sameIdentity");
        if (input.isEmpty() && !remainder.isEmpty()) {
            throw new IllegalArgumentException("empty insert input cannot have a remainder");
        }
        if (!remainder.isEmpty()) {
            if (input.getItem() != remainder.getItem()
                    || !sameIdentity.test(input.copyWithCount(1), remainder.copyWithCount(1))) {
                throw new IllegalArgumentException("insert remainder must have the input item identity");
            }
        }
        if (remainder.getCount() > input.getCount()) {
            throw new IllegalArgumentException("insert remainder exceeds input count");
        }
    }

    private static List<ItemStack> validatedExtracted(long requestedAmount, List<ItemStack> stacks) {
        if (requestedAmount < 0) throw new IllegalArgumentException("requested amount must not be negative");
        Objects.requireNonNull(stacks, "stacks");
        List<ItemStack> copied = copyStacks(stacks);
        if (stackCount(copied) > requestedAmount) {
            throw new IllegalArgumentException("extracted amount exceeds requested amount");
        }
        return copied;
    }

    private static void requireFailure(StorageOperationStatus status) {
        Objects.requireNonNull(status, "status");
        if (status == StorageOperationStatus.SUCCESS || status == StorageOperationStatus.PARTIAL
                || status == StorageOperationStatus.NOT_FOUND || status == StorageOperationStatus.REJECTED
                || status == StorageOperationStatus.INDETERMINATE) {
            throw new IllegalArgumentException("not a failure status: " + status);
        }
    }

    private static void requireDiagnostic(StorageDiagnosticCode diagnosticCode) {
        Objects.requireNonNull(diagnosticCode, "diagnosticCode");
        if (diagnosticCode == StorageDiagnosticCode.NONE) {
            throw new IllegalArgumentException("indeterminate operation requires a diagnostic code");
        }
    }

    private static void requireFailureDiagnostic(StorageOperationStatus status,
                                                 StorageDiagnosticCode diagnosticCode) {
        Objects.requireNonNull(diagnosticCode, "diagnosticCode");
        boolean required = status == StorageOperationStatus.FAILED
                || status == StorageOperationStatus.INVALID_RESPONSE;
        if (required == (diagnosticCode == StorageDiagnosticCode.NONE)) {
            throw new IllegalArgumentException(
                    "failed or invalid operations require a diagnostic code");
        }
        if ((status == StorageOperationStatus.DENIED
                || status == StorageOperationStatus.INVALID_REQUEST)
                && diagnosticCode != StorageDiagnosticCode.NONE) {
            throw new IllegalArgumentException("operation status cannot carry a diagnostic code");
        }
    }

    private void requireKind(Kind expected, String field) {
        if (kind != expected) {
            throw new IllegalStateException(field + " is not available for " + kind + " results");
        }
    }

    private static long stackCount(List<ItemStack> stacks) {
        long total = 0;
        for (ItemStack stack : stacks) total = Math.addExact(total, stack.getCount());
        return total;
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        List<ItemStack> copy = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            Objects.requireNonNull(stack, "stack");
            if (!stack.isEmpty()) copy.add(stack.copy());
        }
        return List.copyOf(copy);
    }

    private static ItemStack copyOrEmpty(ItemStack stack) {
        return stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }

    private static boolean sameSerializedIdentity(ItemStack left, ItemStack right) {
        ItemStack normalizedLeft = left.copyWithCount(1);
        ItemStack normalizedRight = right.copyWithCount(1);
        CompoundTag leftIdentity = normalizedLeft.save(new CompoundTag());
        CompoundTag rightIdentity = normalizedRight.save(new CompoundTag());
        return leftIdentity.equals(rightIdentity);
    }
}
