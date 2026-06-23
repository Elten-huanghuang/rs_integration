package com.huanghuang.rsintegration.module.goety;


import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;

public final class GoetyRSModule {

    public static void initCommon() {
        GoetyRSNetworkHandler.register();
        GoetyGuiNetworkHandler.register();
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
