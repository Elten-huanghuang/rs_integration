package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.sidepanel.client.RSIKeyBindings;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Client-only bootstrap for storage-backend-neutral controls. */
@OnlyIn(Dist.CLIENT)
public final class StorageClientBootstrap {
    private StorageClientBootstrap() {}

    public static void register() {
        RSIKeyBindings.registerKeyMappings();
        StorageSearchClient.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                com.huanghuang.rsintegration.network.binding.BindingTooltipHandler.class);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                com.huanghuang.rsintegration.network.binding.BindingHintOverlay.class);
    }
}
