package com.huanghuang.rsintegration.storage.bd;

import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeyondDimensionsItemKeysTest extends BootstrapTest {
    private static final StorageBackendId BACKEND = new StorageBackendId("beyonddimensions");

    @Test
    void graphNormalizedStackMatchesBdPersistedEmptyTag() {
        ItemStack graphStack = new ItemStack(Items.REDSTONE_BLOCK);
        ItemStack persistedStack = new ItemStack(Items.REDSTONE_BLOCK);
        persistedStack.setTag(new CompoundTag());

        var graphKey = BeyondDimensionsItemKeys.fromStack(BACKEND, graphStack);
        var persistedKey = BeyondDimensionsItemKeys.fromStack(BACKEND, persistedStack);

        assertEquals(graphKey, persistedKey);
        assertFalse(graphKey.backendPayload().contains("tag"));
        assertEquals(new CompoundTag(), persistedKey.backendPayload().getCompound("tag"));
    }

    @Test
    void graphNormalizedStackMatchesDefaultDamageTag() {
        ItemStack graphStack = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack persistedStack = new ItemStack(Items.DIAMOND_PICKAXE);
        persistedStack.getOrCreateTag().putInt("Damage", 0);

        assertEquals(BeyondDimensionsItemKeys.fromStack(BACKEND, graphStack),
                BeyondDimensionsItemKeys.fromStack(BACKEND, persistedStack));
    }

    @Test
    void extractionUsesTheStoredPayloadAfterCanonicalMatching() {
        ItemStack requestedStack = new ItemStack(Items.REDSTONE_BLOCK);
        ItemStack storedStack = new ItemStack(Items.REDSTONE_BLOCK);
        storedStack.setTag(new CompoundTag());
        var requested = BeyondDimensionsItemKeys.fromStack(BACKEND, requestedStack);
        var stored = BeyondDimensionsItemKeys.fromStack(BACKEND, storedStack);
        StorageSnapshot snapshot = new StorageSnapshot(BACKEND, java.util.List.of(
                new StoredItem(stored, 64)));

        var resolved = BeyondDimensionsSession.storedPayloadKey(snapshot, requested);

        assertEquals(stored, resolved);
        assertTrue(resolved.backendPayload().contains("tag"));
    }
}
