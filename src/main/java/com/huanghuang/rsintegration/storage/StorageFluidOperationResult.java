package com.huanghuang.rsintegration.storage;

import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** 流体模拟/实际操作的不可变结果。 */
public record StorageFluidOperationResult(
        StorageOperationResult.Kind kind,
        StorageOperationMode mode,
        StorageOperationStatus status,
        long requestedAmount,
        long transferredAmount,
        boolean transferKnown,
        List<FluidStack> transferredFluids,
        List<FluidStack> recoveryFluids,
        @Nullable FluidStack remainder,
        StorageDiagnosticCode diagnosticCode
) {
    public StorageFluidOperationResult {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(diagnosticCode, "diagnosticCode");
        if (requestedAmount < 0L || transferredAmount < 0L) {
            throw new IllegalArgumentException("fluid amounts must be non-negative");
        }
        transferredFluids = copy(transferredFluids);
        recoveryFluids = copy(recoveryFluids);
        remainder = remainder == null || remainder.isEmpty()
                ? FluidStack.EMPTY : remainder.copy();
    }

    public static StorageFluidOperationResult extracted(
            StorageOperationMode mode,
            long requested,
            List<FluidStack> extracted
    ) {
        List<FluidStack> copy = copy(extracted);
        long transferred = amount(copy);
        StorageOperationStatus status = transferred == requested
                ? StorageOperationStatus.SUCCESS
                : transferred == 0L
                        ? StorageOperationStatus.NOT_FOUND
                        : StorageOperationStatus.PARTIAL;
        return new StorageFluidOperationResult(
                StorageOperationResult.Kind.EXTRACT,
                mode,
                status,
                requested,
                transferred,
                true,
                copy,
                List.of(),
                FluidStack.EMPTY,
                StorageDiagnosticCode.NONE
        );
    }

    public static StorageFluidOperationResult inserted(
            StorageOperationMode mode,
            FluidStack input,
            FluidStack remainder
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(remainder, "remainder");
        long requested = input.getAmount();
        long remaining = remainder.isEmpty() ? 0L : remainder.getAmount();
        long transferred = requested - remaining;
        StorageOperationStatus status = transferred == requested
                ? StorageOperationStatus.SUCCESS
                : transferred == 0L
                        ? StorageOperationStatus.REJECTED
                        : StorageOperationStatus.PARTIAL;
        return new StorageFluidOperationResult(
                StorageOperationResult.Kind.INSERT,
                mode,
                status,
                requested,
                transferred,
                true,
                List.of(),
                List.of(),
                remainder,
                StorageDiagnosticCode.NONE
        );
    }

    public static StorageFluidOperationResult failedExtraction(
            StorageOperationMode mode,
            long requested,
            StorageOperationStatus status,
            List<FluidStack> confirmed,
            List<FluidStack> recovery,
            StorageDiagnosticCode diagnosticCode
    ) {
        List<FluidStack> confirmedCopy = copy(confirmed);
        return new StorageFluidOperationResult(
                StorageOperationResult.Kind.EXTRACT,
                mode,
                status,
                requested,
                amount(confirmedCopy),
                true,
                confirmedCopy,
                recovery,
                FluidStack.EMPTY,
                diagnosticCode
        );
    }

    public static StorageFluidOperationResult failedInsert(
            StorageOperationMode mode,
            FluidStack input,
            StorageOperationStatus status,
            StorageDiagnosticCode diagnosticCode
    ) {
        return new StorageFluidOperationResult(
                StorageOperationResult.Kind.INSERT,
                mode,
                status,
                input == null || input.isEmpty() ? 0L : input.getAmount(),
                0L,
                true,
                List.of(),
                List.of(),
                input,
                diagnosticCode
        );
    }

    public OptionalLong transferredAmountExact() {
        return transferKnown
                ? OptionalLong.of(transferredAmount)
                : OptionalLong.empty();
    }

    private static long amount(List<FluidStack> stacks) {
        long total = 0L;
        for (FluidStack stack : stacks) {
            total = Long.MAX_VALUE - total < stack.getAmount()
                    ? Long.MAX_VALUE : total + stack.getAmount();
        }
        return total;
    }

    private static List<FluidStack> copy(List<FluidStack> stacks) {
        if (stacks == null) return List.of();
        List<FluidStack> result = new ArrayList<>(stacks.size());
        for (FluidStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                result.add(stack.copy());
            }
        }
        return List.copyOf(result);
    }
}
