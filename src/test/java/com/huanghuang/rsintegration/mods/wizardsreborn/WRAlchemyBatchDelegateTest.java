package com.huanghuang.rsintegration.mods.wizardsreborn;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.recipe.WRAlchemyRecipeHandler.MachineInput;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class WRAlchemyBatchDelegateTest extends BootstrapTest {
    @Test
    void requiresEmptyTankWithEnoughCapacityForExactFluidInput() {
        FluidTank tank = new FluidTank(1000);
        assertTrue(WRAlchemyBatchDelegate.canFillEmptyTank(tank, new FluidStack(Fluids.WATER, 750)));
        assertFalse(WRAlchemyBatchDelegate.canFillEmptyTank(tank, new FluidStack(Fluids.WATER, 1001)));
        tank.setFluid(new FluidStack(Fluids.WATER, 1));
        assertFalse(WRAlchemyBatchDelegate.canFillEmptyTank(tank, new FluidStack(Fluids.WATER, 750)));
    }

    @Test
    void mixedOutputWaitsForBothItemAndExactFluidIdentity() {
        ItemStackHandler output = new ItemStackHandler(1);
        FluidTank tank = new FluidTank(5000);
        ItemStack item = new ItemStack(Items.DIAMOND, 2);
        FluidStack fluid = new FluidStack(Fluids.WATER, 1000);
        fluid.getOrCreateTag().putString("variant", "alchemy");
        output.setStackInSlot(0, item.copy());
        assertFalse(WRAlchemyBatchDelegate.hasOutput(output, tank, item, fluid));
        tank.setFluid(new FluidStack(Fluids.WATER, 1000));
        assertFalse(WRAlchemyBatchDelegate.hasOutput(output, tank, item, fluid));
        tank.setFluid(fluid.copy());
        assertTrue(WRAlchemyBatchDelegate.hasOutput(output, tank, item, fluid));
        output.setStackInSlot(0, new ItemStack(Items.DIAMOND));
        assertFalse(WRAlchemyBatchDelegate.hasOutput(output, tank, item, fluid));
    }

    @Test
    void collectsSolidFluidAndContainerRemaindersExactlyOnce() throws ReflectiveOperationException {
        Harness harness = harness(new ItemStack(Items.DIAMOND, 2), new FluidStack(Fluids.WATER, 1000));
        harness.machine.itemOutputHandler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 2));
        harness.boiler.tank.setFluid(new FluidStack(Fluids.WATER, 1000));
        harness.machine.itemHandler.setStackInSlot(3, new ItemStack(Items.GLASS_BOTTLE));

        try (MockedStatic<InkFluidSupport> support = mockStatic(InkFluidSupport.class, CALLS_REAL_METHODS)) {
            support.when(() -> InkFluidSupport.token(any(FluidStack.class))).thenAnswer(call -> token(call.getArgument(0)));
            List<ItemStack> collected = harness.delegate.collectAllResults(mock(ServerPlayer.class));
            assertEquals(3, collected.size());
            assertEquals(2, collected.get(0).getCount());
            assertTrue(collected.get(0).is(Items.DIAMOND));
            assertTrue(new FluidStack(Fluids.WATER, 1000).isFluidStackIdentical(InkFluidSupport.fluid(collected.get(1))));
            assertTrue(collected.get(2).is(Items.GLASS_BOTTLE));
            assertTrue(harness.machine.itemOutputHandler.getStackInSlot(0).isEmpty());
            assertTrue(harness.boiler.tank.isEmpty());
            assertTrue(harness.machine.itemHandler.getStackInSlot(3).isEmpty());
            assertTrue(harness.delegate.collectAllResults(mock(ServerPlayer.class)).isEmpty());
        }
    }

    @Test
    void cleanupDoesNotRefundConsumedInputsWhenProductsAlreadyExist() throws ReflectiveOperationException {
        Harness harness = harness(new ItemStack(Items.DIAMOND), new FluidStack(Fluids.WATER, 1000));
        harness.machine.itemOutputHandler.setStackInSlot(0, new ItemStack(Items.DIAMOND));
        harness.boiler.tank.setFluid(new FluidStack(Fluids.WATER, 1000));
        harness.delegate.clearMachineState(harness.machine, null);
        assertTrue(harness.delegate.failureRecoveredInputs().isEmpty());
        assertTrue(harness.delegate.failureConsumesInputs(null));
        assertEquals(1000, harness.boiler.tank.getFluidAmount());
        assertTrue(harness.machine.itemOutputHandler.getStackInSlot(0).is(Items.DIAMOND));
    }

    @Test
    void cleanupBeforePlacementPreservesExternallyStartedCraft() throws ReflectiveOperationException {
        Harness harness = harness(new ItemStack(Items.DIAMOND), FluidStack.EMPTY);
        set(harness.delegate, "started", false);
        harness.machine.startCraft = true;
        harness.machine.wissenIsCraft = 20;
        harness.delegate.clearMachineState(harness.machine, null);
        assertTrue(harness.machine.startCraft);
        assertEquals(20, harness.machine.wissenIsCraft);
    }

    @Test
    void cleanupBeforeStartDoesNotCountOldOutputsAsThisOperationsProduction() throws ReflectiveOperationException {
        Harness harness = harness(new ItemStack(Items.DIAMOND), FluidStack.EMPTY);
        set(harness.delegate, "started", false);
        harness.machine.itemOutputHandler.setStackInSlot(0, new ItemStack(Items.DIAMOND));
        harness.delegate.clearMachineState(harness.machine, null);
        assertFalse(harness.delegate.failureConsumesInputs(null));
        assertTrue(harness.machine.itemOutputHandler.getStackInSlot(0).is(Items.DIAMOND));
    }

    @Test
    void cancellationRecoversOnlyRecordedInputsAndStopsOwnedCraft() throws ReflectiveOperationException {
        Harness harness = harness(new ItemStack(Items.DIAMOND), FluidStack.EMPTY);
        ItemStack item = new ItemStack(Items.IRON_INGOT, 2);
        FluidStack water = new FluidStack(Fluids.WATER, 600);
        ItemStack fluid = token(water);
        harness.machine.itemHandler.setStackInSlot(0, item.copy());
        harness.machine.itemHandler.setStackInSlot(1, new ItemStack(Items.GOLD_INGOT));
        harness.machine.getTank(0).setFluid(water.copy());
        harness.machine.startCraft = true;
        harness.machine.wissenIsCraft = 20;
        recorded(harness.delegate, "placedItems").add(new WRAlchemyBatchDelegate.PlacedItem(0, item));
        recorded(harness.delegate, "placedFluids").add(new WRAlchemyBatchDelegate.PlacedFluid(0, fluid));
        harness.delegate.clearMachineState(harness.machine, null);
        List<ItemStack> recovered = harness.delegate.failureRecoveredInputs();
        assertEquals(2, recovered.size());
        assertEquals(2, recovered.get(0).getCount());
        assertTrue(water.isFluidStackIdentical(InkFluidSupport.fluid(recovered.get(1))));
        assertTrue(harness.machine.itemHandler.getStackInSlot(0).isEmpty());
        assertTrue(harness.machine.getTank(0).isEmpty());
        assertTrue(harness.machine.itemHandler.getStackInSlot(1).is(Items.GOLD_INGOT));
        assertFalse(harness.machine.startCraft);
        assertEquals(0, harness.machine.wissenIsCraft);
    }

    @Test
    void cancellationDrainsFreeWaterWithoutRefundingItAsReservedMaterial() throws ReflectiveOperationException {
        Harness harness = harness(new ItemStack(Items.DIAMOND), FluidStack.EMPTY);
        harness.machine.startCraft = true;
        harness.machine.getTank(0).setFluid(new FluidStack(Fluids.WATER, 750));
        harness.machine.getTank(1).setFluid(new FluidStack(Fluids.LAVA, 500));
        recorded(harness.delegate, "placedFluids").add(new WRAlchemyBatchDelegate.PlacedFluid(
                0, token(new FluidStack(Fluids.WATER, 750)), false));
        recorded(harness.delegate, "placedFluids").add(new WRAlchemyBatchDelegate.PlacedFluid(
                1, token(new FluidStack(Fluids.LAVA, 500)), true));
        harness.delegate.clearMachineState(harness.machine, null);
        assertTrue(harness.machine.getTank(0).isEmpty());
        assertTrue(harness.machine.getTank(1).isEmpty());
        assertFalse(harness.machine.startCraft);
        assertEquals(1, harness.delegate.failureRecoveredInputs().size());
        assertEquals(Fluids.LAVA, InkFluidSupport.fluid(harness.delegate.failureRecoveredInputs().get(0)).getFluid());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void partialMixedFluidPlacementRefundsPaidInputsWithFreeWaterInEitherPosition(boolean freeWaterFirst)
            throws ReflectiveOperationException {
        Harness harness = harness(new ItemStack(Items.DIAMOND), FluidStack.EMPTY);
        set(harness.delegate, "started", false);
        harness.delegate.setStorageEndpoint(mock(CraftStorageEndpoint.class));
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        ItemStack water = token(new FluidStack(Fluids.WATER, 750));
        ItemStack lava = token(new FluidStack(Fluids.LAVA, 500));
        IngredientSpec ironSpec = spec(iron);
        MachineInput free = new MachineInput(spec(water), true);
        MachineInput paid = new MachineInput(spec(lava), false);
        set(harness.delegate, "machineInputs", List.of(new MachineInput(ironSpec, false),
                freeWaterFirst ? free : paid, freeWaterFirst ? paid : free));
        set(harness.delegate, "required", List.of(ironSpec, paid.material()));
        harness.machine.tanks[1] = new FluidTank(5000) {
            @Override
            public int fill(FluidStack resource, FluidAction action) {
                // 模拟时容量足够，实际投入时仅接收部分，验证失败回收。
                FluidStack accepted = resource.copy();
                if (action.execute()) accepted.shrink(1);
                return super.fill(accepted, action);
            }
        };
        try (MockedStatic<WRAlchemyAccess> access = mockStatic(WRAlchemyAccess.class, CALLS_REAL_METHODS)) {
            access.when(() -> WRAlchemyAccess.number(any(), anyString())).thenReturn(0);
            assertFalse(harness.delegate.tryStartWithMaterials(mock(ServerPlayer.class),
                    List.of(iron, lava), new ExtractionLedger()));
        }
        assertTrue(harness.machine.itemHandler.getStackInSlot(0).is(Items.IRON_INGOT));
        assertEquals(freeWaterFirst ? Fluids.WATER : Fluids.LAVA, harness.machine.getTank(0).getFluid().getFluid());
        assertEquals(freeWaterFirst ? 750 : 500, harness.machine.getTank(0).getFluidAmount());
        assertEquals(freeWaterFirst ? Fluids.LAVA : Fluids.WATER, harness.machine.getTank(1).getFluid().getFluid());
        assertEquals(freeWaterFirst ? 499 : 749, harness.machine.getTank(1).getFluidAmount());
        harness.delegate.clearMachineState(harness.machine, null);
        List<ItemStack> recovered = harness.delegate.failureRecoveredInputs();
        assertEquals(1, recovered.stream().filter(stack -> stack.is(Items.IRON_INGOT))
                .mapToInt(ItemStack::getCount).sum());
        assertEquals(500, recovered.stream().filter(InkFluidSupport::isToken)
                .mapToInt(ItemStack::getCount).sum());
        assertTrue(recovered.stream().filter(InkFluidSupport::isToken)
                .allMatch(stack -> InkFluidSupport.fluid(stack).getFluid() == Fluids.LAVA));
        assertTrue(harness.machine.itemHandler.getStackInSlot(0).isEmpty());
        assertTrue(harness.machine.getTank(0).isEmpty());
        assertTrue(harness.machine.getTank(1).isEmpty());
    }

    private static IngredientSpec spec(ItemStack stack) {
        return new IngredientSpec(StrictNBTIngredient.of(stack.copyWithCount(1)), stack.getCount());
    }

    private static Harness harness(ItemStack item, FluidStack fluid) throws ReflectiveOperationException {
        BlockPos pos = new BlockPos(0, 64, 0);
        TestMachine machine = new TestMachine(pos);
        TestBoiler boiler = new TestBoiler(pos.above());
        ServerLevel level = mock(ServerLevel.class);
        when(level.hasChunkAt(any(BlockPos.class))).thenReturn(true);
        when(level.getBlockEntity(pos)).thenReturn(machine);
        when(level.getBlockEntity(pos.above())).thenReturn(boiler);
        WRAlchemyBatchDelegate delegate = new WRAlchemyBatchDelegate();
        set(delegate, "level", level);
        set(delegate, "pos", pos);
        set(delegate, "machine", machine);
        set(delegate, "boiler", boiler);
        set(delegate, "expectedItem", item);
        set(delegate, "expectedFluid", fluid);
        set(delegate, "started", true);
        return new Harness(delegate, machine, boiler);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> recorded(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (List<Object>) field.get(target);
    }

    private static ItemStack token(FluidStack fluid) {
        return InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), fluid);
    }

    private record Harness(WRAlchemyBatchDelegate delegate, TestMachine machine, TestBoiler boiler) {}

    public static class TestMachine extends BlockEntity {
        public final ItemStackHandler itemHandler = new ItemStackHandler(6);
        public final ItemStackHandler itemOutputHandler = new ItemStackHandler(1);
        public boolean startCraft;
        public int wissenIsCraft;
        public int steamIsCraft;
        private final FluidTank[] tanks = {new FluidTank(5000), new FluidTank(5000), new FluidTank(5000)};

        TestMachine(BlockPos pos) { super(BlockEntityType.CHEST, pos, Blocks.CHEST.defaultBlockState()); }
        public FluidTank getTank(int index) { return tanks[index]; }
    }

    public static class TestBoiler extends BlockEntity {
        private final FluidTank tank = new FluidTank(5000);
        TestBoiler(BlockPos pos) { super(BlockEntityType.CHEST, pos, Blocks.CHEST.defaultBlockState()); }
        public FluidTank getTank() { return tank; }
    }
}
