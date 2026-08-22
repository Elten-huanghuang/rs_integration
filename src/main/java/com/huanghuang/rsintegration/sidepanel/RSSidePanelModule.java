package com.huanghuang.rsintegration.sidepanel;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.fml.ModList;

public final class RSSidePanelModule {

    private RSSidePanelModule() {}

    public static boolean isEnabled() {
        // The side panel is an RS-only UI. Its availability must be decided at
        // the boundary, while the rest of RSI remains fully usable without RS.
        return ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                && RSIntegrationConfig.ENABLE_RS_SIDE_PANEL.get();
    }

    public static void initCommon() {
        if (!isEnabled()) return;
        RSSidePanelNetworkHandler.register();
    }

    public static void initClient() {
        if (!isEnabled()) return;
        RSSidePanelClient.init();
    }
}
