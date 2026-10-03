package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterType;

import java.util.Objects;

final class RSMagnetFluidFilter {
    private RSMagnetFluidFilter() {}

    static boolean matches(ContentsFilterLogic filter, FluidStack fluid) {
        if (filter.getFilterType() == ContentsFilterType.STORAGE) {
            return filter.matchesFilter(InkFluidSupport.token(fluid).copyWithCount(1));
        }
        var slots = filter.getFilterHandler();
        boolean found = false;
        for (int slot = 0; slot < slots.getSlots(); slot++) {
            ItemStack entry = slots.getStackInSlot(slot);
            FluidStack listed = InkFluidSupport.isToken(entry) ? InkFluidSupport.fluid(entry)
                    : FluidUtil.getFluidContained(entry).orElse(FluidStack.EMPTY);
            // 凭据共用物品 ID，必须按流体身份比较，且忽略拖入时的液体数量。
            if (!listed.isEmpty() && listed.getFluid() == fluid.getFluid()
                    && (!filter.shouldMatchNbt() || Objects.equals(listed.getTag(), fluid.getTag()))) {
                found = true;
                break;
            }
        }
        return filter.getFilterType() == ContentsFilterType.ALLOW ? found : !found;
    }
}
