package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbersOutputRecoveryTest extends BootstrapTest {
    @Test
    void drainsOnlyTheFluidStillInTheOutputTank() {
        FluidTank tank = new FluidTank(2000);
        tank.setFluid(new FluidStack(Fluids.WATER, 300));

        FluidStack recovered = EmbersOutputRecovery.drainFluid(
                tank, new FluidStack(Fluids.WATER, 1000));

        assertEquals(300, recovered.getAmount());
        assertTrue(tank.getFluid().isEmpty());
    }

    @Test
    void leavesUnrelatedFluidUntouched() {
        FluidTank tank = new FluidTank(2000);
        tank.setFluid(new FluidStack(Fluids.LAVA, 300));

        FluidStack recovered = EmbersOutputRecovery.drainFluid(
                tank, new FluidStack(Fluids.WATER, 1000));

        assertTrue(recovered.isEmpty());
        assertEquals(300, tank.getFluidAmount());
    }

}
