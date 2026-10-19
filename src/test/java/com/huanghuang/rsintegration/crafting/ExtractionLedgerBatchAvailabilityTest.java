package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExtractionLedgerBatchAvailabilityTest extends BootstrapTest {

    @Test
    void batchAvailabilityAggregatesAcrossIntSizedStacksWithoutOverflow() {
        Inventory inventory = new Inventory(mock(Player.class));
        inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 1_500_000_000));
        inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 1_500_000_000));
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getInventory()).thenReturn(inventory);

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            assertEquals(3_000_000_000L,
                    ledger.countAvailableExactForBatch(
                            new ItemStack(Items.COBBLESTONE), player));
        }
    }
}
