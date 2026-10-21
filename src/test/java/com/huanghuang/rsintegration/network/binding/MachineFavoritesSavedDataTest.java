package com.huanghuang.rsintegration.network.binding;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineFavoritesSavedDataTest {
    @Test
    void enforcesPerPlayerLimitAndPreservesInsertionOrder() {
        MachineFavoritesSavedData data = new MachineFavoritesSavedData();
        UUID player = UUID.randomUUID();
        for (int index = 0; index < MachineFavoritesSavedData.MAX_FAVORITES; index++) {
            assertEquals(MachineFavoritesSavedData.ToggleResult.ADDED,
                    data.toggle(player, key(index)));
        }

        assertEquals(MachineFavoritesSavedData.ToggleResult.LIMIT_REACHED,
                data.toggle(player, key(99)));
        assertEquals(MachineFavoritesSavedData.MAX_FAVORITES,
                data.getFavorites(player).size());
        assertEquals(key(0), data.getFavorites(player).get(0));
    }

    @Test
    void keepsPlayersIsolatedAndRemovesOnlyTheRequestedMachine() {
        MachineFavoritesSavedData data = new MachineFavoritesSavedData();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        data.toggle(first, key(1));
        data.toggle(first, key(2));
        data.toggle(second, key(3));

        assertTrue(data.remove(first, key(1)));
        assertEquals(java.util.List.of(key(2)), data.getFavorites(first));
        assertEquals(java.util.List.of(key(3)), data.getFavorites(second));
        assertFalse(data.remove(first, key(1)));
    }

    @Test
    void removesFavoritesAtUnboundMachineEvenWhenLegacyBlockKeyDiffers() {
        MachineFavoritesSavedData data = new MachineFavoritesSavedData();
        UUID player = UUID.randomUUID();
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
        BlockPos machinePos = new BlockPos(12, 64, -9);
        MachineFavoriteKey legacy = new MachineFavoriteKey(dimension, machinePos,
                "oldmod||block.oldmod.machine");
        MachineFavoriteKey other = key(3);
        data.toggle(player, legacy);
        data.toggle(player, other);

        assertTrue(data.removeAt(player, dimension, machinePos));
        assertEquals(java.util.List.of(other), data.getFavorites(player));
    }

    @Test
    void roundTripsSavedData() {
        MachineFavoritesSavedData original = new MachineFavoritesSavedData();
        UUID player = UUID.randomUUID();
        original.toggle(player, key(4));
        original.toggle(player, key(5));
        original.toggleBlocked(player, key(6));

        CompoundTag encoded = original.save(new CompoundTag());
        MachineFavoritesSavedData decoded = MachineFavoritesSavedData.load(encoded);

        assertEquals(original.getFavorites(player), decoded.getFavorites(player));
        assertEquals(original.getBlocked(player), decoded.getBlocked(player));
    }

    @Test
    void blockedMachinesAreIndependentFromFavoritesAndRemovedByPosition() {
        MachineFavoritesSavedData data = new MachineFavoritesSavedData();
        UUID player = UUID.randomUUID();
        MachineFavoriteKey blocked = key(7);
        MachineFavoriteKey favorite = key(8);

        assertEquals(MachineFavoritesSavedData.ToggleResult.ADDED,
                data.toggleBlocked(player, blocked));
        data.toggle(player, favorite);
        assertTrue(data.isBlocked(player, blocked));
        assertEquals(java.util.List.of(blocked), data.getBlocked(player));
        assertEquals(java.util.List.of(favorite), data.getFavorites(player));

        assertTrue(data.removeAt(player, blocked.dimension(), blocked.pos()));
        assertFalse(data.isBlocked(player, blocked));
        assertEquals(java.util.List.of(favorite), data.getFavorites(player));
    }

    @Test
    void batchBlocklistUpdatesOnlyChangedEntriesAndSavesOncePerOperation() {
        MachineFavoritesSavedData data = new MachineFavoritesSavedData();
        UUID player = UUID.randomUUID();
        MachineFavoriteKey first = key(10);
        MachineFavoriteKey second = key(11);
        data.toggleBlocked(player, first);

        assertEquals(1, data.setBlocked(player, List.of(first, second), true));
        assertEquals(List.of(first, second), data.getBlocked(player));
        assertEquals(0, data.setBlocked(player, List.of(first, second), true));
        assertEquals(2, data.setBlocked(player, List.of(first, second), false));
        assertTrue(data.getBlocked(player).isEmpty());
    }

    private static MachineFavoriteKey key(int index) {
        return new MachineFavoriteKey(new ResourceLocation("minecraft", "overworld"),
                new BlockPos(index, 64, -index), "block.example.machine_" + index);
    }
}
