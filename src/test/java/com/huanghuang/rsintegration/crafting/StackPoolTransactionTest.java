package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StackPoolTransactionTest extends BootstrapTest {

    @Test
    void failedReservationDoesNotShrinkEitherPool() {
        List<ItemStack> initial = new ArrayList<>(List.of(new ItemStack(Items.IRON_INGOT, 3)));
        List<ItemStack> producer = new ArrayList<>(List.of(new ItemStack(Items.DIAMOND, 2)));

        Object result = StackPoolTransaction.execute(initial, producer, (workingInitial, workingProducer) -> {
            workingInitial.get(0).shrink(1);
            workingProducer.get(0).shrink(2);
            return null;
        });

        assertNull(result);
        assertEquals(3, initial.get(0).getCount());
        assertEquals(2, producer.get(0).getCount());
    }

    @Test
    void successfulReservationCommitsBothPools() {
        List<ItemStack> initial = new ArrayList<>(List.of(new ItemStack(Items.IRON_INGOT, 3)));
        List<ItemStack> producer = new ArrayList<>(List.of(new ItemStack(Items.DIAMOND, 2)));

        Integer result = StackPoolTransaction.execute(initial, producer, (workingInitial, workingProducer) -> {
            workingInitial.get(0).shrink(1);
            workingProducer.get(0).shrink(2);
            return 7;
        });

        assertEquals(7, result);
        assertEquals(2, initial.get(0).getCount());
        assertEquals(0, producer.get(0).getCount());
    }
}
