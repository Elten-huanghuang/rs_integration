package com.huanghuang.rsintegration.sidepanel;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public final class RSSidePanelModule {

    private RSSidePanelModule() {}

    public static void initCommon() {
        RSSidePanelNetworkHandler.register();
    }

    @OnlyIn(Dist.CLIENT)
    public static void initClient() {
        RSSidePanelClient.init();
    }
}
