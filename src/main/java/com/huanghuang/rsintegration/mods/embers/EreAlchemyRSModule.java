package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.BindingEventHandler;

import java.util.List;

public final class EreAlchemyRSModule {

    public static void initCommon() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "embers", ModType.EMBERS_ALCHEMY,
                RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY, List.of(
                "com.rekindled.embers.block.AlchemyTabletBlock"
        ), null));

        RSIntegrationMod.LOGGER.debug("Embers Alchemy RS module common init done.");
    }
}
