package com.huanghuang.rsintegration.module.wizards_reborn;

import com.huanghuang.rsintegration.RSIntegrationMod;

public final class WizardsRebornRSModule {

    public static void initCommon() {
        WRWandNetworkHandler.register();
        RSIntegrationMod.LOGGER.debug("Wizards Reborn RS module common init done.");
    }
}
