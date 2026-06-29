package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.ModCraftNetworkHandlers;
import com.huanghuang.rsintegration.network.BindingEventHandler;

import java.util.List;

public final class MalumRSModule {

    public static void initCommon() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "malum", ModType.MALUM, RSIntegrationConfig.ENABLE_MALUM, List.of(
                "com.sammy.malum.common.block.curiosities.spirit_altar.SpiritAltarBlock"
        ), "malum"));

        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "malum", ModType.MALUM_SPIRIT_CRUCIBLE, RSIntegrationConfig.ENABLE_MALUM, List.of(
                "com.sammy.malum.common.block.curiosities.spirit_crucible.SpiritCrucibleCoreBlock"
        ), "malum_spirit_crucible"));

        ModCraftNetworkHandlers.registerMalum();
        RSIntegrationMod.LOGGER.debug("Malum RS module common init done.");
    }
}
