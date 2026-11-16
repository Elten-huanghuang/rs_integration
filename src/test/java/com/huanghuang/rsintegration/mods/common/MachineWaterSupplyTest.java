package com.huanghuang.rsintegration.mods.common;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineWaterSupplyTest extends BootstrapTest {
    @Test
    void freeWaterNeverTouchesStorage() {
        FluidTank tank = new FluidTank(1000);
        assertEquals(1000, MachineWaterSupply.fill(tank, 1000, true,
                ignored -> { throw new AssertionError("免费补水不应提取库存"); },
                ignored -> { throw new AssertionError("免费水不应返还库存"); }));
        assertEquals(1000, tank.getFluidAmount());
    }

    @Test
    void paidWaterOnlyChargesTheAmountAddedAndPreservesExistingWater() {
        FluidTank tank = new FluidTank(2000);
        tank.fill(water(750), IFluidHandler.FluidAction.EXECUTE);
        AtomicInteger charged = new AtomicInteger();
        assertEquals(250, MachineWaterSupply.fill(tank, 250, false, requested -> {
            charged.addAndGet(requested);
            return water(requested);
        }, ignored -> { throw new AssertionError("成功补水不应退款"); }));
        assertEquals(250, charged.get());
        assertEquals(1000, tank.getFluidAmount());
    }

    @Test
    void insufficientStorageWaterIsRefundedWithoutFilling() {
        FluidTank tank = new FluidTank(1000);
        List<FluidStack> refunds = new ArrayList<>();
        assertEquals(0, MachineWaterSupply.fill(tank, 1000, false, ignored -> water(500), refunds::add));
        assertEquals(0, tank.getFluidAmount());
        assertEquals(500, refunds.get(0).getAmount());
    }

    @Test
    void incompatibleOrFullTankDoesNotExtractWaterOrOverwriteItsFluid() {
        FluidTank tank = new FluidTank(1000);
        tank.fill(new FluidStack(Fluids.LAVA, 500), IFluidHandler.FluidAction.EXECUTE);
        assertEquals(0, MachineWaterSupply.fill(tank, 1000, false,
                ignored -> { throw new AssertionError("拒收水时不应扣水"); },
                ignored -> { throw new AssertionError("未扣水时不应退款"); }));
        assertEquals(Fluids.LAVA, tank.getFluid().getFluid());
        assertEquals(500, tank.getFluidAmount());
    }

    @Test
    void changedTankCapacityRollsBackOnlyTheNewWaterAndRefundsIt() {
        FluidTank tank = partialTank();
        tank.setFluid(water(200));
        List<FluidStack> refunds = new ArrayList<>();
        assertEquals(0, MachineWaterSupply.fill(tank, 500, false, MachineWaterSupplyTest::water, refunds::add));
        assertEquals(200, tank.getFluidAmount());
        assertEquals(500, refunds.get(0).getAmount());
    }

    @Test
    void failedFreeFillDoesNotCreateWaterInStorage() {
        FluidTank tank = partialTank();
        assertEquals(0, MachineWaterSupply.fill(tank, 500, true,
                ignored -> { throw new AssertionError("免费补水不应提取库存"); },
                ignored -> { throw new AssertionError("免费水不应返还库存"); }));
        assertEquals(0, tank.getFluidAmount());
    }

    @Test
    void incorrectExtractedFluidIsRefundedRatherThanInserted() {
        FluidTank tank = new FluidTank(1000);
        List<FluidStack> refunds = new ArrayList<>();
        assertEquals(0, MachineWaterSupply.fill(tank, 500, false,
                ignored -> new FluidStack(Fluids.LAVA, 500), refunds::add));
        assertEquals(0, tank.getFluidAmount());
        assertEquals(Fluids.LAVA, refunds.get(0).getFluid());
    }

    @Test
    void freePropertyUpdateDoesNotTouchStorage() {
        assertTrue(MachineWaterSupply.fillProperty(1000, true,
                ignored -> { throw new AssertionError("免费补水不应提取库存"); },
                ignored -> { throw new AssertionError("免费水不应返还库存"); }, () -> true));
    }

    @Test
    void insufficientPaidPropertyWaterDoesNotUpdateTheMachine() {
        List<FluidStack> refunds = new ArrayList<>();
        assertFalse(MachineWaterSupply.fillProperty(1000, false, ignored -> water(500), refunds::add,
                () -> { throw new AssertionError("缺水时不应修改机器"); }));
        assertEquals(500, refunds.get(0).getAmount());
    }

    @Test
    void failedPropertyUpdateRefundsAllHeldWater() {
        List<FluidStack> refunds = new ArrayList<>();
        assertFalse(MachineWaterSupply.fillProperty(1000, false, MachineWaterSupplyTest::water,
                refunds::add, () -> false));
        assertEquals(1000, refunds.get(0).getAmount());
    }

    @Test
    void exceptionalPropertyUpdateAlsoRefundsHeldWater() {
        List<FluidStack> refunds = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> MachineWaterSupply.fillProperty(1000, false,
                MachineWaterSupplyTest::water, refunds::add,
                () -> { throw new IllegalStateException("机器已失效"); }));
        assertEquals(1000, refunds.get(0).getAmount());
    }

    @Test
    void freePreflightDoesNotFillTheTank() {
        FluidTank tank = new FluidTank(1000);
        assertTrue(MachineWaterSupply.canFill("eidolon_crucible", tank, 1000, null, null));
        assertEquals(0, tank.getFluidAmount());
        assertFalse(MachineWaterSupply.canFill("eidolon_crucible", tank, 1001, null, null));
        assertFalse(MachineWaterSupply.canFill("unknown_machine", tank, 1000, null, null));
    }

    private static FluidTank partialTank() {
        return new FluidTank(1000) {
            @Override
            public int fill(FluidStack resource, FluidAction action) {
                FluidStack accepted = resource.copy();
                if (action.execute()) accepted.setAmount(resource.getAmount() / 2);
                return super.fill(accepted, action);
            }
        };
    }

    private static FluidStack water(int amount) {
        return new FluidStack(Fluids.WATER, amount);
    }
}
