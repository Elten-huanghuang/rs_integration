package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.BindingEventHandler;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;

import java.util.List;

public final class GoetyRSModule {

    public static void initCommon() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "goety", ModType.GOETY, RSIntegrationConfig.ENABLE_GOETY, List.of(
                "com.Polarice3.Goety.common.blocks.DarkAltarBlock",
                "com.Polarice3.Goety.common.blocks.NecroBrazierBlock",
                "com.Polarice3.Goety.common.blocks.CursedCageBlock",
                "com.Polarice3.Goety.common.blocks.SoulCandlestickBlock"
        ), "goety"));

        GoetyRSNetworkHandler.register();
        GoetyGuiNetworkHandler.register();
        RSIntegrationMod.LOGGER.debug("Goety RS module common init done.");
    }

    @OnlyIn(Dist.CLIENT)
    public static void initClient() {
        MinecraftForge.EVENT_BUS.register(GoetyGuiClientEventHandler.class);
    }

    public static void onJeiRuntimeAvailable(IJeiRuntime jeiRuntime) {
    }

    public static void onJeiRuntimeUnavailable() {
        RSClientAvailabilityCache.clear();
    }

    public static void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
    }
}
