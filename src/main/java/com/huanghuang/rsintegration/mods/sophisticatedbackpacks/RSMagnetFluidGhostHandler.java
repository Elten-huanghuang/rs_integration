package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
import net.p3pp3rf1y.sophisticatedcore.common.gui.IFilterSlot;
import net.p3pp3rf1y.sophisticatedcore.compat.jei.SetGhostSlotMessage;
import net.p3pp3rf1y.sophisticatedcore.network.PacketHandler;

import java.util.ArrayList;
import java.util.List;

public final class RSMagnetFluidGhostHandler {
    private RSMagnetFluidGhostHandler() {}

    public static <I> List<IGhostIngredientHandler.Target<I>> targets(StorageScreenBase<?> screen, FluidStack fluid) {
        List<IGhostIngredientHandler.Target<I>> targets = new ArrayList<>();
        var open = screen.getMenu().getOpenContainer().orElse(null);
        if (fluid.isEmpty() || !(open instanceof RSMagnetUpgradeContainer)) return targets;
        ItemStack ghost = InkFluidSupport.token(fluid).copyWithCount(1);
        for (Slot slot : open.getSlots()) {
            if (!(slot instanceof IFilterSlot) || !slot.isActive() || !slot.mayPlace(ghost)) continue;
            targets.add(new IGhostIngredientHandler.Target<>() {
                @Override
                public Rect2i getArea() {
                    return new Rect2i(screen.getGuiLeft() + slot.x, screen.getGuiTop() + slot.y, 17, 17);
                }

                @Override
                public void accept(I ingredient) {
                    PacketHandler.INSTANCE.sendToServer(new SetGhostSlotMessage(ghost.copy(), slot.index));
                }
            });
        }
        return targets;
    }
}
