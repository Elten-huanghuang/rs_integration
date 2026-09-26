package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.sidepanel.client.RSIKeyBindings;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import com.huanghuang.rsintegration.network.binding.BindingHintOverlay;
import com.huanghuang.rsintegration.network.binding.BindingTooltipHandler;
import net.minecraftforge.common.MinecraftForge;

/** Client-only bootstrap for storage-backend-neutral controls. */
@OnlyIn(Dist.CLIENT)
public final class StorageClientBootstrap {
    private StorageClientBootstrap() {}

    public static void register() {
        RSIKeyBindings.registerKeyMappings();
        StorageSearchClient.register();
        MinecraftForge.EVENT_BUS.register(
                BindingTooltipHandler.class);
        MinecraftForge.EVENT_BUS.register(
                BindingHintOverlay.class);
    }
}
