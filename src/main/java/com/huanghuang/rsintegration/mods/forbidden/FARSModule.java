package com.huanghuang.rsintegration.mods.forbidden;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.ModCraftNetworkHandlers;
import com.huanghuang.rsintegration.network.BindingEventHandler;

import java.util.List;

public final class FARSModule {

    public static void initCommon() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "forbidden_arcanus", ModType.FORBIDDEN_ARCANUS,
                RSIntegrationConfig.ENABLE_FORBIDDEN_ARCANUS, List.of(
                "com.stal111.forbidden_arcanus.common.block.forge.HephaestusForgeBlock"
        ), "forbidden_arcanus"));

        ModCraftNetworkHandlers.registerFa();
        RSIntegrationMod.LOGGER.debug("Forbidden & Arcanus RS module common init done.");
    }
}
