package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.BindingEventHandler;

import java.util.List;

public final class AetherworksRSModule {

    private AetherworksRSModule() {}

    public static void initCommon() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "aetherworks", ModType.AETHERWORKS_ANVIL,
                RSIntegrationConfig.ENABLE_AETHERWORKS,
                List.of("net.sirplop.aetherworks.block.forge.AetheriumAnvilBlock"),
                "aetherworks"
        ));
        RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] Common init done.");
    }
}
