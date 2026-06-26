package com.huanghuang.rsintegration.mods.wizards_reborn;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.ModCraftNetworkHandlers;
import com.huanghuang.rsintegration.network.BindingEventHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;

import java.util.List;

public final class WizardsRebornRSModule {

    public static void initCommon() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "wizards_reborn", ModType.WIZARDS_REBORN,
                RSIntegrationConfig.ENABLE_WIZARDS_REBORN, List.of(
                "mod.maxbogomol.wizards_reborn.common.block.wissen_crystallizer.WissenCrystallizerBlock",
                "mod.maxbogomol.wizards_reborn.common.block.arcane_iterator.ArcaneIteratorBlock",
                "mod.maxbogomol.wizards_reborn.common.block.arcane_workbench.ArcaneWorkbenchBlock"
        ), "wizards_reborn"));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "wizards_reborn", ModType.WIZARDS_REBORN,
                RSIntegrationConfig.ENABLE_WIZARDS_REBORN, List.of(
                "mod.maxbogomol.wizards_reborn.common.block.crystal.CrystalBlock"
        ), "crystal_ritual"));

        ModCraftNetworkHandlers.registerWRWand();
        RSIntegrationMod.LOGGER.debug("Wizards Reborn RS module common init done.");
    }

    @OnlyIn(Dist.CLIENT)
    public static void initClient() {
        MinecraftForge.EVENT_BUS.register(WRGuiClientEventHandler.class);
    }
}
