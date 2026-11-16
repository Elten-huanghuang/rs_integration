package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CrockPotPreparedOperationTest extends BootstrapTest {
    @Test
    void incompleteOrOversizedPreparedMaterialsAreRejectedBeforeTouchingTheMachine() throws Exception {
        CrockPotBatchDelegate delegate = new CrockPotBatchDelegate();
        var slots = CrockPotBatchDelegate.class.getDeclaredField("potLevel");
        slots.setAccessible(true);
        slots.setInt(delegate, CrockPotRecipeHandler.INPUT_SLOT_COUNT);
        ServerPlayer player = mock(ServerPlayer.class);
        ExtractionLedger ledger = mock(ExtractionLedger.class);

        assertFalse(delegate.startPreparedOperation(player, List.of(), ledger));
        assertFalse(delegate.startPreparedOperation(player,
                List.of(new ItemStack(Items.COD), new ItemStack(Items.STICK, 2)), ledger));
        assertFalse(delegate.startPreparedOperation(player,
                List.of(new ItemStack(Items.COD), new ItemStack(Items.STICK, 4)), ledger));
        verifyNoInteractions(player, ledger);
    }
}
