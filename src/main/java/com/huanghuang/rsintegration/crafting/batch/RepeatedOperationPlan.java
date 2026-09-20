package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Lazy operation-major material matrix for a multi-worker dispatch.
 *
 * <p>The matrix keeps one flat immutable backing list. Per-operation lists are
 * created only after a worker claims them, avoiding one allocation per queued
 * operation while retaining the stable one-operation {@link MaterialPlan}.</p>
 */
public final class RepeatedOperationPlan {
    private final MaterialPlan materialPlan;
    private final int operations;
    private final int legacyStride;
    private final List<ItemStack> flatMaterials;

    public RepeatedOperationPlan(MaterialPlan materialPlan, int operations,
                                 int legacyStride, List<ItemStack> flatMaterials) {
        if (operations <= 0) throw new IllegalArgumentException("operations must be positive");
        if (legacyStride <= 0) throw new IllegalArgumentException("legacy stride must be positive");
        this.materialPlan = materialPlan == null ? MaterialPlan.none() : materialPlan;
        if (this.materialPlan.entries().isEmpty()) {
            throw new IllegalArgumentException("per-operation material plan must not be empty");
        }
        long expectedSize = (long) operations * legacyStride;
        if (expectedSize > Integer.MAX_VALUE || flatMaterials == null
                || flatMaterials.size() != (int) expectedSize) {
            throw new IllegalArgumentException("flat material matrix does not match operation stride");
        }
        for (MaterialPlan.Entry entry : this.materialPlan.entries()) {
            if (entry.inputSlot() == null || entry.inputSlot() >= legacyStride) {
                throw new IllegalArgumentException(
                        "repeated material entry lacks a compatible legacy slot: " + entry.id());
            }
        }
        List<ItemStack> copy = new ArrayList<>(flatMaterials.size());
        for (ItemStack material : flatMaterials) {
            copy.add(material == null || material.isEmpty() ? ItemStack.EMPTY : material.copy());
        }
        this.operations = operations;
        this.legacyStride = legacyStride;
        this.flatMaterials = List.copyOf(copy);
        validateRequiredEntries();
    }

    public MaterialPlan materialPlan() {
        return materialPlan;
    }

    public int operations() {
        return operations;
    }

    public int legacyStride() {
        return legacyStride;
    }

    public ItemStack materialAt(int operation, int legacySlot) {
        if (operation < 0 || operation >= operations) {
            throw new IndexOutOfBoundsException("operation: " + operation);
        }
        if (legacySlot < 0 || legacySlot >= legacyStride) {
            throw new IndexOutOfBoundsException("legacy slot: " + legacySlot);
        }
        ItemStack material = flatMaterials.get(operation * legacyStride + legacySlot);
        return material.isEmpty() ? ItemStack.EMPTY : material.copy();
    }

    public List<ItemStack> legacyMaterials(int operation) {
        if (operation < 0 || operation >= operations) {
            throw new IndexOutOfBoundsException("operation: " + operation);
        }
        int offset = operation * legacyStride;
        List<ItemStack> slice = new ArrayList<>(legacyStride);
        for (int slot = 0; slot < legacyStride; slot++) {
            ItemStack material = flatMaterials.get(offset + slot);
            slice.add(material.isEmpty() ? ItemStack.EMPTY : material.copy());
        }
        return List.copyOf(slice);
    }

    /** Compatibility projection for the old outer group adapter only. */
    public List<ItemStack> flatLegacyMaterials() {
        return flatMaterials.stream()
                .map(stack -> stack.isEmpty() ? ItemStack.EMPTY : stack.copy()).toList();
    }

    private void validateRequiredEntries() {
        for (int operation = 0; operation < operations; operation++) {
            for (MaterialPlan.Entry entry : materialPlan.entries()) {
                ItemStack material = flatMaterials.get(
                        operation * legacyStride + entry.inputSlot());
                if (material == null || material.isEmpty()
                        || material.getCount() != entry.spec().count()
                        || !entry.spec().ingredient().test(material)) {
                    throw new IllegalArgumentException("invalid material for operation " + operation
                            + " entry " + entry.id());
                }
            }
        }
    }
}
