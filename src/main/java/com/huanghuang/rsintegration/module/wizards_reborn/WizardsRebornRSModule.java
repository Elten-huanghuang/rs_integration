package com.huanghuang.rsintegration.module.wizards_reborn;

import com.huanghuang.rsintegration.RSIntegrationMod;

public final class WizardsRebornRSModule {

    public static void initCommon() {
        com.huanghuang.rsintegration.module.ModCraftNetworkHandlers.registerWRWand();
        RSIntegrationMod.LOGGER.debug("Wizards Reborn RS module common init done.");
    }
}
