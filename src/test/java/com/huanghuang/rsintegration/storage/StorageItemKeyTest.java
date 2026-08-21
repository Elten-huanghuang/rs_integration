package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageItemKeyTest extends BootstrapTest {
    @Test
    void identityAndDisplayStackAreDefensivelyCopied() {
        CompoundTag identity = new CompoundTag();
        identity.putString("caps", "original");
        ItemStack display = new ItemStack(Items.DIAMOND, 5);
        StorageItemKey key = new StorageItemKey(new StorageBackendId("test"), identity, display);
        identity.putString("caps", "changed");
        display.setCount(1);

        assertEquals("original", key.identity().getString("caps"));
        assertEquals(1, key.displayStack().getCount());
        key.identity().putString("caps", "returned_changed");
        assertEquals("original", key.identity().getString("caps"));
    }

    @Test
    void backendQualificationIsPartOfExactIdentity() {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        assertNotEquals(StorageItemKey.fromItemStack(new StorageBackendId("rs"), stack),
                StorageItemKey.fromItemStack(new StorageBackendId("bd"), stack));
    }

    @Test
    void backendCanonicalIdentityOverridesPayloadEquality() {
        CompoundTag firstPayload = new CompoundTag();
        firstPayload.putString("native", "first");
        CompoundTag secondPayload = new CompoundTag();
        secondPayload.putString("native", "second");
        StorageBackendId backend = new StorageBackendId("relaxed");

        StorageItemKey first = StorageItemKey.withCanonicalIdentity(backend, firstPayload,
                new byte[] { 1, 2, 3 }, new ItemStack(Items.DIAMOND));
        StorageItemKey second = StorageItemKey.withCanonicalIdentity(backend, secondPayload,
                new byte[] { 1, 2, 3 }, new ItemStack(Items.DIAMOND));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first.backendPayload(), second.backendPayload());
    }

    @Test
    void canonicalComponentsCannotMergeDifferentItemTypes() {
        CompoundTag payload = new CompoundTag();
        payload.putString("components", "same");
        StorageBackendId backend = new StorageBackendId("bd");

        StorageItemKey diamond = StorageItemKey.withCanonicalIdentity(backend, payload,
                new byte[] { 1, 2, 3 }, new ItemStack(Items.DIAMOND));
        StorageItemKey gold = StorageItemKey.withCanonicalIdentity(backend, payload,
                new byte[] { 1, 2, 3 }, new ItemStack(Items.GOLD_INGOT));

        assertNotEquals(diamond, gold);
        assertNotEquals(diamond.hashCode(), gold.hashCode());
    }

    @Test
    void defaultIdentityIsStableAcrossCompoundInsertionOrderAndPayloadChanges() {
        CompoundTag first = new CompoundTag();
        first.putInt("a", 1);
        first.putInt("b", 2);
        CompoundTag reordered = new CompoundTag();
        reordered.putInt("b", 2);
        reordered.putInt("a", 1);
        StorageBackendId backend = new StorageBackendId("exact");

        assertEquals(new StorageItemKey(backend, first, new ItemStack(Items.DIAMOND)),
                new StorageItemKey(backend, reordered, new ItemStack(Items.DIAMOND)));

        CompoundTag changed = reordered.copy();
        changed.putInt("b", 3);
        assertNotEquals(new StorageItemKey(backend, first, new ItemStack(Items.DIAMOND)),
                new StorageItemKey(backend, changed, new ItemStack(Items.DIAMOND)));
    }

    @Test
    void vanillaIdentityFoldsSignedZeroAndRejectsNan() {
        ItemStack positiveZero = new ItemStack(Items.DIAMOND);
        positiveZero.getOrCreateTag().putFloat("value", 0.0f);
        ItemStack negativeZero = new ItemStack(Items.DIAMOND);
        negativeZero.getOrCreateTag().putFloat("value", -0.0f);
        ItemStack nan = new ItemStack(Items.DIAMOND);
        nan.getOrCreateTag().putDouble("value", Double.NaN);
        StorageBackendId backend = new StorageBackendId("vanilla");

        assertEquals(StorageItemKey.fromItemStack(backend, positiveZero),
                StorageItemKey.fromItemStack(backend, negativeZero));
        assertThrows(IllegalArgumentException.class,
                () -> StorageItemKey.fromItemStack(backend, nan));
    }

    @Test
    void emptyListElementTypeIsFoldedLikeVanillaIdentity() throws IOException {
        CompoundTag bytes = new CompoundTag();
        bytes.put("values", emptyListOf(Tag.TAG_BYTE));
        CompoundTag ints = new CompoundTag();
        ints.put("values", emptyListOf(Tag.TAG_INT));
        StorageBackendId backend = new StorageBackendId("vanilla");

        assertEquals(bytes, ints);
        assertEquals(new StorageItemKey(backend, bytes, new ItemStack(Items.DIAMOND)),
                new StorageItemKey(backend, ints, new ItemStack(Items.DIAMOND)));
    }

    private static ListTag emptyListOf(byte elementType) throws IOException {
        byte[] encoded = { elementType, 0, 0, 0, 0 };
        return ListTag.TYPE.load(new DataInputStream(new ByteArrayInputStream(encoded)),
                0, NbtAccounter.UNLIMITED);
    }
}
