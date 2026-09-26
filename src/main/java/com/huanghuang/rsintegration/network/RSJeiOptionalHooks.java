package com.huanghuang.rsintegration.network;

import com.huanghuang.rsintegration.sidepanel.client.MachineFavoritesClient;
import com.huanghuang.rsintegration.autoeat.client.AutoEatClientEvents;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import java.util.ArrayList;
import java.util.List;

/** RS-only JEI hooks. This class must only be loaded when RS is present. */
public final class RSJeiOptionalHooks {
    private RSJeiOptionalHooks() {}

    public static void registerGridGuiHandler(IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(GridScreen.class,
                new IGuiContainerHandler<GridScreen>() {
                    @Override
                    public List<Rect2i> getGuiExtraAreas(GridScreen screen) {
                        List<Rect2i> areas = new ArrayList<>();
                        areas.addAll(MachineFavoritesClient.getJeiExtraAreas(screen));
                        areas.addAll(AutoEatClientEvents.getGuiExtraAreas(screen));
                        return List.copyOf(areas);
                    }
                });
    }
}
