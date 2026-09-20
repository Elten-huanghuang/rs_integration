package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class VanillaMachineBatchDelegateReflectionTest {
    @Test
    void legacyFurnaceStartKeepsTheInjectedBatchSize() {
        assertEquals(6, VanillaMachineBatchDelegate.furnaceOperationsFromMaterials(
                List.of(new ItemStack(Items.IRON_ORE, 6))));
        assertEquals(1, VanillaMachineBatchDelegate.furnaceOperationsFromMaterials(List.of()));
        assertEquals(1, VanillaMachineBatchDelegate.furnaceOperationsFromMaterials(
                List.of(ItemStack.EMPTY)));
    }

    @Test
    void furnaceBufferIsBoundByInputOutputAndConfiguredCapacity() {
        assertEquals(64, VanillaMachineBatchDelegate.safeFurnaceBufferOperations(
                96, 64, 64, 64, 1));
        assertEquals(16, VanillaMachineBatchDelegate.safeFurnaceBufferOperations(
                64, 64, 64, 16, 1));
        assertEquals(0, VanillaMachineBatchDelegate.safeFurnaceBufferOperations(
                64, 64, 64, 16, 0));
    }

    @Test
    void fallsBackToRuntimeFieldNameWithoutTreatingTheFirstMissAsFailure() {
        Field field = VanillaMachineBatchDelegate.findDeclaredField(
                RuntimeFields.class, "developmentName", "runtimeName");

        assertNotNull(field);
        assertEquals("runtimeName", field.getName());
    }

    @Test
    void returnsNullOnlyAfterEveryCandidateMisses() {
        assertNull(VanillaMachineBatchDelegate.findDeclaredField(
                RuntimeFields.class, "developmentName", "otherRuntimeName"));
    }

    @Test
    void declaresSlotWorldAndVirtualPrimaryOutputChannels() {
        ItemStack result = new ItemStack(Items.IRON_INGOT, 3);

        OutputContract slot = VanillaMachineBatchDelegate.vanillaPrimaryOutputContract(
                result, OutputContract.Source.SLOT, 2);
        OutputContract world = VanillaMachineBatchDelegate.vanillaPrimaryOutputContract(
                result, OutputContract.Source.WORLD, null);
        OutputContract virtual = VanillaMachineBatchDelegate.vanillaPrimaryOutputContract(
                result, OutputContract.Source.VIRTUAL, null);

        assertEquals(2, slot.ports().get(0).physicalPort());
        assertEquals(3, slot.ports().get(0).perOperation());
        assertEquals(InputBufferPlan.OutputPort.Kind.PRIMARY, slot.ports().get(0).kind());
        assertEquals(OutputContract.Source.WORLD, world.ports().get(0).source());
        assertEquals(OutputContract.Source.VIRTUAL, virtual.ports().get(0).source());
    }

    private static final class RuntimeFields {
        @SuppressWarnings("unused")
        private int runtimeName;
    }
}
