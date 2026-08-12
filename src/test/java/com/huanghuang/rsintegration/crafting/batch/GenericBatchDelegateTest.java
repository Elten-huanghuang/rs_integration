package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericBatchDelegateTest extends BootstrapTest {

    @Test
    void collectAllResultsDrainsPrimaryAndRecipeRemaindersTogether() throws Exception {
        GenericBatchDelegate delegate = new GenericBatchDelegate();
        setField(delegate, "pendingResult", new ItemStack(Items.DIAMOND, 2));
        pendingSecondary(delegate).add(new ItemStack(Items.BUCKET));
        pendingSecondary(delegate).add(new ItemStack(Items.STICK, 3));

        List<ItemStack> results = delegate.collectAllResults(null);

        assertEquals(3, results.size());
        assertEquals(2, results.get(0).getCount());
        assertEquals(Items.DIAMOND, results.get(0).getItem());
        assertEquals(Items.BUCKET, results.get(1).getItem());
        assertEquals(3, results.get(2).getCount());
        assertEquals(Items.STICK, results.get(2).getItem());
        assertTrue(delegate.collectsPhysicalSecondaryOutputs());
        assertTrue(delegate.collectAllResults(null).isEmpty());
        assertTrue(delegate.getPendingSecondary().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> pendingSecondary(GenericBatchDelegate delegate) throws Exception {
        Field field = GenericBatchDelegate.class.getDeclaredField("pendingSecondary");
        field.setAccessible(true);
        return (List<ItemStack>) field.get(delegate);
    }

    private static void setField(GenericBatchDelegate delegate, String name, Object value) throws Exception {
        Field field = GenericBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(delegate, value);
    }
}
