package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class CraftItemRecoveryTest extends BootstrapTest {
    @Test
    void mergesAndRoundTripsPendingRefunds() {
        UUID owner = UUID.randomUUID();
        CraftItemRecovery recovery = new CraftItemRecovery();
        recovery.retain(owner, new ItemStack(Items.IRON_INGOT, 3));
        recovery.retain(owner, new ItemStack(Items.IRON_INGOT, 4));

        CraftItemRecovery loaded =
                new CraftItemRecovery(recovery.save(new CompoundTag()));
        assertEquals(1, loaded.pendingStacksForTests(owner).size());
        assertEquals(7, loaded.pendingStacksForTests(owner).get(0).getCount());
    }

    @Test
    void claimRemovesOnlyStacksAcceptedByThePlayerInventory() {
        ServerPlayer player = mock(ServerPlayer.class);
        UUID owner = UUID.randomUUID();
        when(player.getUUID()).thenReturn(owner);
        when(player.getInventory()).thenReturn(new Inventory(player));
        CraftItemRecovery recovery = new CraftItemRecovery();
        recovery.retain(owner, new ItemStack(Items.IRON_INGOT, 2));

        try (MockedStatic<PlayerUtils> playerUtils = mockStatic(PlayerUtils.class)) {
            playerUtils.when(() -> PlayerUtils.insertIntoPlayerInventory(
                    eq(player), any(ItemStack.class))).thenReturn(ItemStack.EMPTY);
            assertEquals(1, recovery.claim(player));
        }

        assertFalse(recovery.hasPending(owner));
    }
}
