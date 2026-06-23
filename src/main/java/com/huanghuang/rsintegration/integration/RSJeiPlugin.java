package com.huanghuang.rsintegration.integration;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.module.goety.GoetyRSModule;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.NotNull;

@JeiPlugin
public final class RSJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = new ResourceLocation(RSIntegrationMod.MOD_ID, "main");

    @Override
    public @NotNull ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(@NotNull IJeiRuntime jeiRuntime) {
        if (!RSIntegrationConfig.ENABLE_JEI.get()) return;
        if (RSIntegrationConfig.ENABLE_GOETY.get() && ModList.get().isLoaded("goety")) {
            GoetyRSModule.onJeiRuntimeAvailable(jeiRuntime);
        }
    }

    @Override
    public void onRuntimeUnavailable() {
        if (RSIntegrationConfig.ENABLE_GOETY.get() && ModList.get().isLoaded("goety")) {
            GoetyRSModule.onJeiRuntimeUnavailable();
        }
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        if (RSIntegrationConfig.ENABLE_GOETY.get() && ModList.get().isLoaded("goety")) {
            GoetyRSModule.registerRecipeTransferHandlers(registration);
        }
    }
}
