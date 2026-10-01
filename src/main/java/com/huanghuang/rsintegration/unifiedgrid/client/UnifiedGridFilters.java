package com.huanghuang.rsintegration.unifiedgrid.client;

import com.refinedmods.refinedstorage.api.util.IFilter;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/** 物品白名单只约束物品，流体白名单只约束流体；模组过滤跨两类生效。 */
public final class UnifiedGridFilters {
    private UnifiedGridFilters() { }

    public static boolean matches(List<IFilter> filters, IGridStack entry) {
        Integer mode = null;
        Object ingredient = entry.getIngredient();
        for (IFilter filter : filters) {
            Object example = filter.getStack();
            boolean sameKind = (ingredient instanceof ItemStack && example instanceof ItemStack)
                    || (ingredient instanceof FluidStack && example instanceof FluidStack);
            if (!sameKind && !filter.isModFilter()) continue;
            mode = filter.getMode();
            boolean matches;
            if (filter.isModFilter()) {
                String mod;
                if (example instanceof ItemStack item) mod = item.getItem().getCreatorModId(item);
                else if (example instanceof FluidStack fluid) {
                    var key = ForgeRegistries.FLUIDS.getKey(fluid.getFluid());
                    mod = key == null ? null : key.getNamespace();
                } else continue;
                matches = mod != null && mod.equalsIgnoreCase(entry.getModId());
            } else if (ingredient instanceof ItemStack item && example instanceof ItemStack other) {
                matches = API.instance().getComparer().isEqual(item, other, filter.getCompare());
            } else {
                matches = API.instance().getComparer().isEqual((FluidStack) ingredient, (FluidStack) example, filter.getCompare());
            }
            if (matches) return mode == 0;
        }
        return mode == null || mode != 0;
    }
}
