package com.huanghuang.rsintegration.mods.immortalersdelight;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantalCoolerBatchDelegateTest extends BootstrapTest {

    @Test
    void everyCoolerOperationConsumesOneLapisLazuli() {
        assertEquals(1, EnchantalCoolerBatchDelegate.requiredLapisForOperations(1));
        assertEquals(4, EnchantalCoolerBatchDelegate.requiredLapisForOperations(4));
        assertEquals(16, EnchantalCoolerBatchDelegate.requiredLapisForOperations(16));
    }

    @Test
    void cancellationRecoversOutputProducedBeforeTheInterrupt() {
        ItemStackHandler handler = new ItemStackHandler(7);
        handler.setStackInSlot(5, new ItemStack(Items.DIAMOND, 6));

        ItemStack recovered = EnchantalCoolerBatchDelegate.extractOwnedOutputForRecovery(
                handler, 5, new ItemStack(Items.DIAMOND), true);

        assertEquals(6, recovered.getCount());
        assertTrue(handler.getStackInSlot(5).isEmpty());
    }

    @Test
    void cancellationDoesNotTakeAnUnownedOrUnexpectedOutput() {
        ItemStackHandler handler = new ItemStackHandler(7);
        handler.setStackInSlot(5, new ItemStack(Items.DIAMOND, 6));

        ItemStack recovered = EnchantalCoolerBatchDelegate.extractOwnedOutputForRecovery(
                handler, 5, new ItemStack(Items.GOLD_INGOT), true);

        assertTrue(recovered.isEmpty());
        assertEquals(6, handler.getStackInSlot(5).getCount());
    }
}
