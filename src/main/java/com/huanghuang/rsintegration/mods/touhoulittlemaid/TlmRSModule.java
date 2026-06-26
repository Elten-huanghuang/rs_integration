package com.huanghuang.rsintegration.mods.touhoulittlemaid;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.BindingEventHandler;

import java.util.List;

public final class TlmRSModule {

    public static void initCommon() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "touhou_little_maid", ModType.TOUHOU_LITTLE_MAID,
                RSIntegrationConfig.ENABLE_TOUHOU_LITTLE_MAID, List.of(
                "com.github.tartaricacid.touhoulittlemaid.block.BlockAltar"
        ), "touhou_little_maid"));

        RSIntegrationMod.LOGGER.debug("Touhou Little Maid RS module common init done.");
    }
}
