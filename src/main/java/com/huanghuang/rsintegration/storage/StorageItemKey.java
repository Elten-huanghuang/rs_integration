package com.huanghuang.rsintegration.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.Objects;

/** Backend-qualified canonical identity, reconstruction payload, and defensive display stack. */
public final class StorageItemKey {
    private final StorageBackendId backendId;
    private final ResourceLocation itemType;
    private final CompoundTag backendPayload;
    private final byte[] canonicalIdentity;
    private final ItemStack displayStack;

    /** Uses deterministic vanilla-compatible NBT identity; NaN is rejected as non-equivalent. */
    public StorageItemKey(StorageBackendId backendId, CompoundTag backendPayload, ItemStack displayStack) {
        this(backendId, backendPayload, StorageIdentityBytes.exact(
                Objects.requireNonNull(backendPayload, "backendPayload")), displayStack);
    }

    private StorageItemKey(StorageBackendId backendId, CompoundTag backendPayload,
                           byte[] canonicalIdentity, ItemStack displayStack) {
        this.backendId = Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(backendPayload, "backendPayload");
        Objects.requireNonNull(canonicalIdentity, "canonicalIdentity");
        Objects.requireNonNull(displayStack, "displayStack");
        if (backendPayload.isEmpty()) throw new IllegalArgumentException("storage item payload must not be empty");
        if (canonicalIdentity.length == 0) {
            throw new IllegalArgumentException("canonical storage identity must not be empty");
        }
        if (displayStack.isEmpty()) throw new IllegalArgumentException("display stack must not be empty");
        this.itemType = BuiltInRegistries.ITEM.getResourceKey(displayStack.getItem())
                .orElseThrow(() -> new IllegalArgumentException("display stack item is not registered"))
                .location();
        this.backendPayload = backendPayload.copy();
        this.canonicalIdentity = canonicalIdentity.clone();
        this.displayStack = displayStack.copyWithCount(1);
    }

    /** Creates a key using the backend's authoritative equality/hash representation. */
    public static StorageItemKey withCanonicalIdentity(StorageBackendId backendId,
                                                       CompoundTag backendPayload,
                                                       byte[] canonicalIdentity,
                                                       ItemStack displayStack) {
        return new StorageItemKey(backendId, backendPayload, canonicalIdentity, displayStack);
    }

    /** Creates a vanilla/Forge stack identity, including serialized capabilities. */
    public static StorageItemKey fromItemStack(StorageBackendId backendId, ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("stack must not be empty");
        ItemStack normalized = stack.copyWithCount(1);
        return new StorageItemKey(backendId, normalized.save(new CompoundTag()), normalized);
    }

    public StorageBackendId backendId() { return backendId; }
    public ResourceLocation itemType() { return itemType; }
    public CompoundTag backendPayload() { return backendPayload.copy(); }
    /** @deprecated use backendPayload(); retained while the adapter prototype is being revised. */
    @Deprecated(forRemoval = false)
    public CompoundTag identity() { return backendPayload(); }
    public byte[] canonicalIdentity() { return canonicalIdentity.clone(); }
    public ItemStack displayStack() { return displayStack.copy(); }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof StorageItemKey key
                && backendId.equals(key.backendId)
                && itemType.equals(key.itemType)
                && Arrays.equals(canonicalIdentity, key.canonicalIdentity);
    }

    @Override
    public int hashCode() {
        int result = 31 * backendId.hashCode() + itemType.hashCode();
        return 31 * result + Arrays.hashCode(canonicalIdentity);
    }
}
