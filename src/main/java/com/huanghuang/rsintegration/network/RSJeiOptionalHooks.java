package com.huanghuang.rsintegration.network;

import com.huanghuang.rsintegration.sidepanel.client.MachineFavoritesClient;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;

/** RS-only JEI hooks. This class must only be loaded when RS is present. */
public final class RSJeiOptionalHooks {
    private RSJeiOptionalHooks() {}

    public static void registerGridGuiHandler(IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(GridScreen.class,
                new IGuiContainerHandler<GridScreen>() {
                    @Override
                    public java.util.List<Rect2i> getGuiExtraAreas(GridScreen screen) {
                        return MachineFavoritesClient.getJeiExtraAreas(screen);
                    }
                });
    }
}
