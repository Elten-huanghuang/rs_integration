package com.huanghuang.rsintegration.sidepanel;

public final class RSSidePanelModule {

    private RSSidePanelModule() {}

    public static void initCommon() {
        RSSidePanelNetworkHandler.register();
    }

    public static void initClient() {
        RSSidePanelClient.init();
    }
}
