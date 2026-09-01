package com.huanghuang.rsintegration.compat.emi;

import com.huanghuang.rsintegration.sidepanel.client.MachineFavoritesClient;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.widget.Bounds;

/** RS-only EMI hooks. This class must only be loaded when RS is present. */
public final class RSEmiOptionalHooks {
    private RSEmiOptionalHooks() {}

    public static void registerGridExclusion(EmiRegistry registry) {
        registry.addExclusionArea(GridScreen.class, (screen, consumer) -> {
            for (var area : MachineFavoritesClient.getJeiExtraAreas(screen)) {
                consumer.accept(new Bounds(
                        area.getX(), area.getY(), area.getWidth(), area.getHeight()));
            }
        });
    }
}
