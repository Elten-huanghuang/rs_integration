package com.huanghuang.rsintegration.storage.bd;

import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Maps graph-normalized stacks back to the exact payload stored by BD. */
final class BeyondDimensionsItemKeys {
    private BeyondDimensionsItemKeys() {}

    static StorageItemKey fromStack(StorageBackendId backendId, ItemStack stack) {
        Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("stack must not be empty");

        ItemStack payloadStack = stack.copyWithCount(1);
        CompoundTag payload = payloadStack.save(new CompoundTag());
        ItemStack canonicalStack = payloadStack.copy();
        CompoundTag tag = canonicalStack.getTag();
        if (tag != null && (tag.isEmpty() || isDefaultDamageTag(canonicalStack, tag))) {
            canonicalStack.setTag(null);
        }
        return StorageItemKey.withCanonicalIdentity(backendId, payload,
                canonicalStack.save(new CompoundTag()), payloadStack);
    }

    private static boolean isDefaultDamageTag(ItemStack stack, CompoundTag tag) {
        return stack.isDamageableItem()
                && tag.size() == 1
                && tag.contains("Damage", Tag.TAG_ANY_NUMERIC)
                && tag.getInt("Damage") == 0;
    }
}
