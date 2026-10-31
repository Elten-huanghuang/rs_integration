package com.huanghuang.rsintegration.feature.goety.test;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.server.ServerStartedEvent;

/** Deliberately has no Goety, RS, or client references. Not shipped in releases. */
@Mod.EventBusSubscriber(modid = "rs_integration")
public final class OptionalDependencySmoke {
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("rs_integration.noGoetySmoke")) return;
        if (net.minecraftforge.fml.ModList.get().isLoaded("goety")) throw new AssertionError("Goety is installed");
        var menu = net.minecraftforge.registries.ForgeRegistries.MENU_TYPES.getValue(
                net.minecraft.resources.ResourceLocation.parse("rs_integration:witch_cauldron"));
        if (menu != null) throw new AssertionError("Optional Goety menu was registered without Goety");
        RSIntegrationMod.LOGGER.info("NO_GOETY_SERVER_PASS: RSI started without Goety, RS or client classes");
        event.getServer().halt(false);
    }
}
