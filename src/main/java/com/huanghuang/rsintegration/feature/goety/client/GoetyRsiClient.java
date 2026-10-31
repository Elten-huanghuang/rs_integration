package com.huanghuang.rsintegration.feature.goety.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;

public final class GoetyRsiClient {
    private GoetyRsiClient() {}

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void setup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(() -> net.minecraft.client.gui.screens.MenuScreens.register(
                com.huanghuang.rsintegration.feature.goety.menu.GoetyRsiMenus.WITCH_CAULDRON.get(), WitchCauldronScreen::new));
    }

    public static void accept(com.huanghuang.rsintegration.feature.goety.network.BrewNetwork.State state) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null && player.containerMenu instanceof com.huanghuang.rsintegration.feature.goety.menu.WitchCauldronMenu menu
                && menu.containerId == state.menuId()) menu.accept(state.data());
    }

}
