package com.huanghuang.rsintegration.feature.goety;

import com.Polarice3.Goety.common.blocks.entities.ModBlockEntities;
import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.api.machine.gui.RSIMachineGuiApi;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.feature.goety.brewing.CauldronInteraction;
import com.huanghuang.rsintegration.feature.goety.menu.GoetyRsiMenus;
import com.huanghuang.rsintegration.feature.goety.network.BrewNetwork;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/** Loaded only after the owning mod confirms Goety is installed. */
public final class GoetyCauldronFeature {
    public static final String MOD_ID = RSIntegrationMod.MOD_ID;
    public static final org.apache.logging.log4j.Logger LOGGER = RSIntegrationMod.LOGGER;

    private GoetyCauldronFeature() {}

    public static void initialize(IEventBus bus) {
        GoetyRsiMenus.MENUS.register(bus);
        BrewNetwork.register();
        bus.addListener(GoetyCauldronFeature::commonSetup);
        MinecraftForge.EVENT_BUS.register(CauldronInteraction.class);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> bus.addListener(
                        com.huanghuang.rsintegration.feature.goety.client.GoetyRsiClient::setup));
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (!RSIntegrationConfig.ENABLE_GOETY.get()) return;
            GoetyRsiRegistrations.register(RSIIntegrationApi.get().orElseThrow());
            if (!RSIMachineGuiApi.register(ModBlockEntities.BREWING_CAULDRON.get(),
                    (player, entity, dimension, position) -> {
                        if (entity instanceof BrewCauldronBlockEntity cauldron) {
                            CauldronInteraction.open(player, cauldron, true);
                        }
                    })) {
                throw new IllegalStateException("Witch cauldron GUI registration was rejected");
            }
        });
    }
}
