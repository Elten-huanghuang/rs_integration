package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IronAlchemistBatchDelegateTest extends BootstrapTest {
    private static InkFluidItem TOKEN;

    @BeforeAll
    static void createTokenItem() { TOKEN = InkFluidTestFixtures.tokenItem(); }

    @Test
    void consumedMissFinishesWithoutPublishingOrRefundingAnOutput() throws Exception {
        IronAlchemistBatchDelegate delegate = new IronAlchemistBatchDelegate();
        setField(delegate, "consumed", true);
        assertEquals("", IronAlchemistBatchDelegate.recyclingFailure(0, 250));
        assertEquals(IBatchDelegate.CraftPhase.DONE,
                delegate.observeMachineCraft(mock(ServerLevel.class), null).phase());
        assertTrue(delegate.collectResult(mock(ServerPlayer.class)).isEmpty());
        assertNull(delegate.getExpectedProduction());
        assertTrue(delegate.failureConsumesInputs(new IBatchDelegate.CraftObservation(IBatchDelegate.CraftPhase.FAILED)));
    }

    @Test
    void abnormalFluidChangesRemainMachineFailures() throws Exception {
        assertEquals("", IronAlchemistBatchDelegate.recyclingFailure(250, 250));
        for (int produced : new int[]{-250, 100, 500}) {
            IronAlchemistBatchDelegate delegate = new IronAlchemistBatchDelegate();
            setField(delegate, "consumed", true);
            setField(delegate, "recycleFailure", IronAlchemistBatchDelegate.recyclingFailure(produced, 250));
            IBatchDelegate.CraftObservation observation = delegate.observeMachineCraft(mock(ServerLevel.class), null);
            assertEquals(IBatchDelegate.CraftPhase.FAILED, observation.phase());
            assertTrue(delegate.failureConsumesInputs(observation));
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private CraftStorageEndpoint endpoint(ItemStack output, int remainder) {
        CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class, RETURNS_DEEP_STUBS);
        when(endpoint.session().reference()).thenReturn(new StorageReference(
                new StorageBackendId("refinedstorage"), "test"));
        when(endpoint.insert(any(ServerPlayer.class), any(ItemStack.class), eq(true)))
                .thenReturn(StorageOperationResult.inserted(StorageOperationMode.SIMULATE,
                        output, remainder == 0 ? ItemStack.EMPTY : output.copyWithCount(remainder)));
        return endpoint;
    }

    @Test
    void fullOrItemOnlyRsLeavesAllFluidInCauldron() {
        assertBlockedCollection(250);
    }

    @Test
    void partialRsCapacityAlsoLeavesAllFluidInCauldron() {
        assertBlockedCollection(1);
    }

    private void assertBlockedCollection(int remainder) {
        FluidTank tank = new FluidTank(1000);
        tank.fill(new FluidStack(Fluids.WATER, 750), IFluidHandler.FluidAction.EXECUTE);
        ItemStack output = InkFluidSupport.token(TOKEN, new FluidStack(Fluids.WATER, 250));
        assertTrue(IronAlchemistBatchDelegate.collectFluid(tank, endpoint(output, remainder),
                mock(ServerPlayer.class), output).isEmpty());
        assertEquals(750, tank.getFluidAmount());
    }

    @Test
    void collectsOnlyThisOperations250MbAndCanReturnItAsNativeFluid() {
        FluidTank tank = new FluidTank(1000);
        tank.fill(new FluidStack(Fluids.WATER, 750), IFluidHandler.FluidAction.EXECUTE);
        ItemStack output = InkFluidSupport.token(TOKEN, new FluidStack(Fluids.WATER, 250));
        ItemStack collected = IronAlchemistBatchDelegate.collectFluid(tank, endpoint(output, 0),
                mock(ServerPlayer.class), output);
        assertEquals(250, collected.getCount());
        assertEquals(500, tank.getFluidAmount());
        assertEquals(250, InkFluidSupport.fluid(collected).getAmount());
    }

    @Test
    void missingMachineOrInsufficientInkDoesNotPublishOutput() {
        ItemStack output = InkFluidSupport.token(TOKEN, new FluidStack(Fluids.WATER, 250));
        CraftStorageEndpoint endpoint = endpoint(output, 0);
        ServerPlayer player = mock(ServerPlayer.class);
        assertTrue(IronAlchemistBatchDelegate.collectFluid(null, endpoint, player, output).isEmpty());
        FluidTank tank = new FluidTank(1000);
        tank.fill(new FluidStack(Fluids.WATER, 249), IFluidHandler.FluidAction.EXECUTE);
        assertTrue(IronAlchemistBatchDelegate.collectFluid(tank, endpoint, player, output).isEmpty());
        assertEquals(249, tank.getFluidAmount());
    }

    @Test
    void partialPhysicalDrainIsRestoredInsteadOfPublishingAPartialProduct() {
        ItemStack output = InkFluidSupport.token(TOKEN, new FluidStack(Fluids.WATER, 250));
        FluidTank tank = spy(new FluidTank(1000));
        tank.fill(new FluidStack(Fluids.WATER, 750), IFluidHandler.FluidAction.EXECUTE);
        doAnswer(invocation -> tank.drain(100, IFluidHandler.FluidAction.EXECUTE))
                .when(tank).drain(any(FluidStack.class), eq(IFluidHandler.FluidAction.EXECUTE));
        assertTrue(IronAlchemistBatchDelegate.collectFluid(tank, endpoint(output, 0),
                mock(ServerPlayer.class), output).isEmpty());
        assertEquals(750, tank.getFluidAmount());
    }

    @Test
    void accountsForWaterTankBecomingEmptyDuringRecycling() {
        FluidTank tank = new FluidTank(1000);
        tank.fill(new FluidStack(Fluids.WATER, 250), IFluidHandler.FluidAction.EXECUTE);
        assertTrue(IronAlchemistBatchDelegate.canFitRecycledInk(tank, new FluidStack(Fluids.LAVA, 250)));
        tank.fill(new FluidStack(Fluids.WATER, 250), IFluidHandler.FluidAction.EXECUTE);
        assertFalse(IronAlchemistBatchDelegate.canFitRecycledInk(tank, new FluidStack(Fluids.LAVA, 250)));
    }
}
