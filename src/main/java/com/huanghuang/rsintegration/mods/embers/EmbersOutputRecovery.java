package com.huanghuang.rsintegration.mods.embers;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

/** 只回收仍在机器中、且身份与本次配方产物相符的部分。 */
final class EmbersOutputRecovery {
    private EmbersOutputRecovery() {}

    static FluidStack drainFluid(IFluidHandler tank, FluidStack expected) {
        FluidStack actual = tank.getFluidInTank(0).copy();
        // 管道可能在完成观察后抽走一部分；只回收罐里仍属于本次配方的液体。
        if (actual.isEmpty() || !actual.isFluidEqual(expected)) return FluidStack.EMPTY;
        return tank.drain(actual, IFluidHandler.FluidAction.EXECUTE);
    }
}
