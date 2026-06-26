package com.huanghuang.rsintegration.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.goety.GoetyRSModule;
import com.huanghuang.rsintegration.sidepanel.RSInventoryTransferHandler;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

@JeiPlugin
public final class RSJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = new ResourceLocation(RSIntegrationMod.MOD_ID, "main");

    @Nullable
    private static IJeiRuntime cachedRuntime;

    @Nullable
    public static IJeiRuntime getRuntime() {
        return cachedRuntime;
    }

    @Override
    public @NotNull ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(@NotNull IJeiRuntime jeiRuntime) {
        cachedRuntime = jeiRuntime;
        if (!RSIntegrationConfig.ENABLE_JEI.get()) return;
        if (RSIntegrationConfig.ENABLE_GOETY.get() && ModList.get().isLoaded("goety")) {
            GoetyRSModule.onJeiRuntimeAvailable(jeiRuntime);
        }
    }

    @Override
    public void onRuntimeUnavailable() {
        cachedRuntime = null;
        if (RSIntegrationConfig.ENABLE_GOETY.get() && ModList.get().isLoaded("goety")) {
            GoetyRSModule.onJeiRuntimeUnavailable();
        }
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        if (RSIntegrationConfig.ENABLE_GOETY.get() && ModList.get().isLoaded("goety")) {
            GoetyRSModule.registerRecipeTransferHandlers(registration);
        }
        if (RSIntegrationConfig.ENABLE_RS_SIDE_PANEL.get()
                && ModList.get().isLoaded("refinedstorage")) {
            registration.addUniversalRecipeTransferHandler(
                    new RSInventoryTransferHandler());
        }
    }
}
