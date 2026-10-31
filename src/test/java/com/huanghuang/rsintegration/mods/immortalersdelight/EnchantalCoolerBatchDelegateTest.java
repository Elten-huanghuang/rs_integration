package com.huanghuang.rsintegration.mods.immortalersdelight;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.reflection.probes.ImmersalsDelightReflection;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

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

    @Test
    void leavesOutputsInTheMachineUntilAllTenOperationsFinish() throws Exception {
        try (CoolerFixture fixture = new CoolerFixture(10)) {
            fixture.inventory.setStackInSlot(0, new ItemStack(Items.APPLE, 10));
            assertEquals(IBatchDelegate.CraftPhase.WORKING, fixture.observe().phase());
            for (int operation = 1; operation <= 10; operation++) {
                fixture.inventory.extractItem(0, 1, false);
                fixture.inventory.setStackInSlot(5, new ItemStack(Items.DIAMOND, operation));
                // 原生冷却器每轮结束都会把计时清零，下一轮尚有输入时不能结束整批。
                fixture.machine.cookingTotalTime = 0;

                assertEquals(operation == 10 ? IBatchDelegate.CraftPhase.DONE
                        : IBatchDelegate.CraftPhase.WORKING, fixture.observe().phase());
                assertEquals(operation, fixture.inventory.getStackInSlot(5).getCount(),
                        "观察进度不得提前取走输出");
            }
            List<ItemStack> results = fixture.delegate.collectAllResults(fixture.player);
            assertEquals(10, results.stream().mapToInt(ItemStack::getCount).sum());
            assertTrue(fixture.delegate.collectAllResults(fixture.player).isEmpty());
        }
    }

    @Test
    void repeatedObservationDoesNotCountTheSameOutputTwice() throws Exception {
        try (CoolerFixture fixture = new CoolerFixture(10)) {
            fixture.inventory.setStackInSlot(0, new ItemStack(Items.APPLE, 9));
            fixture.inventory.setStackInSlot(5, new ItemStack(Items.DIAMOND));
            for (int observation = 0; observation < 20; observation++) {
                assertEquals(IBatchDelegate.CraftPhase.WORKING, fixture.observe().phase());
            }
            assertEquals(1, fixture.inventory.getStackInSlot(5).getCount());
            assertEquals(1, fixture.delegate.collectAllResults(fixture.player)
                    .stream().mapToInt(ItemStack::getCount).sum());
        }
    }

    @Test
    void structuredSettlementCollectsTheWholeBatchAndPreservesNbt() throws Exception {
        try (CoolerFixture fixture = new CoolerFixture(2)) {
            ItemStack output = new ItemStack(Items.DIAMOND, 2);
            output.getOrCreateTag().putInt("production", 7);
            fixture.inventory.setStackInSlot(5, output);
            assertEquals(IBatchDelegate.CraftPhase.DONE, fixture.observe().phase());
            List<OutputAccounting.CollectedOutput> outputs =
                    fixture.delegate.collectStructuredResults(fixture.player);
            assertEquals(1, outputs.size());
            assertEquals(2, outputs.get(0).stack().getCount());
            assertEquals(7, outputs.get(0).stack().getTag().getInt("production"));
            assertEquals("immortalers_delight:cooler:output", outputs.get(0).portId());
            assertEquals(OutputContract.Source.SLOT, outputs.get(0).source());
            assertTrue(fixture.inventory.getStackInSlot(5).isEmpty());
        }
    }

    @Test
    void missingPhysicalOutputsAreNotReplacedWithThePlannedQuantity() throws Exception {
        try (CoolerFixture fixture = new CoolerFixture(10)) {
            fixture.observe();
            fixture.machine.cookingTotalTime = 0;
            fixture.inventory.setStackInSlot(5, new ItemStack(Items.DIAMOND));
            assertEquals(IBatchDelegate.CraftPhase.DONE, fixture.observe().phase());
            List<OutputAccounting.CollectedOutput> outputs =
                    fixture.delegate.collectStructuredResults(fixture.player);
            assertEquals(1, outputs.get(0).stack().getCount());
            OutputContract contract = new OutputContract(List.of(new OutputContract.Port(
                    "immortalers_delight:cooler:output", 5, new ItemStack(Items.DIAMOND),
                    1, OutputContract.Kind.PRIMARY, OutputContract.Source.SLOT)));
            assertFalse(OutputAccounting.assess(contract, 10, outputs).complete());
        }
    }

    private static final class CoolerFixture implements AutoCloseable {
        private static final List<String> REFLECTION_FIELDS = List.of(
                "inventoryField", "residualDyeField", "cookingTotalTimeField",
                "isFuelMethod", "reflectionProbed");
        private final Class<?> previousMachineClass = ImmersalsDelightReflection.enchantalCoolerBEClass;
        private final List<Object> previousReflectionValues = new ArrayList<>();
        private final ItemStackHandler inventory = new ItemStackHandler(7);
        private final CoolerMachine machine = mock(CoolerMachine.class);
        private final ServerLevel level = mock(ServerLevel.class);
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final EnchantalCoolerBatchDelegate delegate = spy(new EnchantalCoolerBatchDelegate());

        private CoolerFixture(int operations) throws Exception {
            for (String name : REFLECTION_FIELDS) {
                previousReflectionValues.add(field(name).get(null));
            }
            ImmersalsDelightReflection.enchantalCoolerBEClass = CoolerMachine.class;
            machine.inventory = inventory;
            machine.cookingTotalTime = 1;
            field("inventoryField").set(null, CoolerMachine.class.getDeclaredField("inventory"));
            field("cookingTotalTimeField").set(null,
                    CoolerMachine.class.getDeclaredField("cookingTotalTime"));
            field("reflectionProbed").set(null, true);
            field("inventoryLease").set(delegate, true);
            field("myLevel").set(delegate, level);
            field("myPos").set(delegate, BlockPos.ZERO);
            field("player").set(delegate, player);
            ItemStack[] suppliedTypes = (ItemStack[]) field("suppliedSlotTypes").get(delegate);
            int[] suppliedCounts = (int[]) field("suppliedSlotCounts").get(delegate);
            suppliedTypes[0] = new ItemStack(Items.APPLE);
            suppliedCounts[0] = operations;
            when(level.hasChunkAt(BlockPos.ZERO)).thenReturn(true);
            when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(machine);
            doReturn(new IBatchDelegate.ExpectedProduction(
                    new ItemStack(Items.DIAMOND), operations))
                    .when(delegate).getExpectedProduction();
        }

        private IBatchDelegate.CraftObservation observe() {
            return delegate.observeMachineCraft(level, machine);
        }

        private static Field field(String name) throws Exception {
            Field field = EnchantalCoolerBatchDelegate.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        @Override
        public void close() throws Exception {
            ImmersalsDelightReflection.enchantalCoolerBEClass = previousMachineClass;
            for (int index = 0; index < REFLECTION_FIELDS.size(); index++) {
                field(REFLECTION_FIELDS.get(index)).set(null, previousReflectionValues.get(index));
            }
        }
    }

    private abstract static class CoolerMachine extends BlockEntity {
        public ItemStackHandler inventory;
        public int cookingTotalTime;

        private CoolerMachine() {
            super(null, BlockPos.ZERO, null);
        }
    }
}
