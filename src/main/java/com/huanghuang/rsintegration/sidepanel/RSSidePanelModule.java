package com.huanghuang.rsintegration.sidepanel;

public final class RSSidePanelModule {

    // Temporarily hard-disabled while the full-sync performance issue is investigated.
    private static final boolean ENABLED = false;

    private RSSidePanelModule() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void initCommon() {
        RSSidePanelNetworkHandler.register();
    }

    public static void initClient() {
        if (!ENABLED) return;
        RSSidePanelClient.init();
    }
}
