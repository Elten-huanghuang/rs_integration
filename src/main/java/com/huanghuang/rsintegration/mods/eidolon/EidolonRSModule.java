package com.huanghuang.rsintegration.mods.eidolon;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.ModCraftNetworkHandlers;
import com.huanghuang.rsintegration.network.BindingEventHandler;

import java.util.List;

public final class EidolonRSModule {

    public static void initCommon() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "eidolon", ModType.EIDOLON, RSIntegrationConfig.ENABLE_EIDOLON, List.of(
                "elucent.eidolon.common.block.CrucibleBlock"
        ), "eidolon"));

        ModCraftNetworkHandlers.registerEidolon();
        RSIntegrationMod.LOGGER.debug("Eidolon RS module common init done.");
    }
}
