package com.huanghuang.rsintegration.feature.sidepanel;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.fml.ModList;

public final class RSSidePanelModule {

    private RSSidePanelModule() {}

    public static boolean isEnabled() {
        // The side panel is an RS-only UI. Its availability must be decided at
        // the boundary, while the rest of RSI remains fully usable without RS.
        return ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                && RSIntegrationConfig.enabled(RSIntegrationConfig.COMMON_SPEC,
                        RSIntegrationConfig.ENABLE_RS_SIDE_PANEL);
    }

    public static void initCommon() {
        // The unified channel also carries machine-tab, remote-GUI, grid
        // swipe, and resonance-backpack packets. Registering it must not be
        // tied to the optional side-panel UI switch.
        RSSidePanelNetworkHandler.register();
    }

    public static void initClient() {
        if (!isEnabled()) return;
        RSSidePanelClient.init();
    }
}
