package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.StorageItemKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Builds keys with RS's authoritative item-and-tag equality while retaining full Forge payloads. */
final class RefinedStorageItemKeys {
    private RefinedStorageItemKeys() {}

    static StorageItemKey fromStack(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("stack must not be empty");
        ItemStack normalized = stack.copyWithCount(1);
        CompoundTag payload = normalized.save(new CompoundTag());
        CompoundTag canonical = new CompoundTag();
        canonical.putString("item", BuiltInRegistries.ITEM.getKey(normalized.getItem()).toString());
        if (normalized.getTag() != null) canonical.put("tag", normalized.getTag().copy());
        return StorageItemKey.withCanonicalIdentity(
                RefinedStorageIds.BACKEND, payload, canonical, normalized);
    }
}
