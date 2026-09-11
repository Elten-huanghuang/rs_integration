package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.network.binding.AltarBinding;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.RSBindingHook;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskFactory;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageResolvers;
import com.huanghuang.rsintegration.resonance.bridge.RSInventoryBridge;
import com.huanghuang.rsintegration.resonance.passive.PassiveEffectEngine;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelClient;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelModule;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.sidepanel.client.RSIKeyBindings;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.apiimpl.API;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;

/** All code in this class is linked only after Forge confirms RS is present. */
public final class RSOptionalBootstrap {
    private RSOptionalBootstrap() {}

    public static void registerItems(IEventBus modBus) {
        ModItems.registerOptionalResonance(modBus,
                () -> com.huanghuang.rsintegration.resonance.item.ResonanceDiskItem.INSTANCE);
        ModItems.registerOptionalVoidUpgrade(modBus,
                com.huanghuang.rsintegration.voidupgrade.RSVoidUpgradeItem::new);
    }

    public static void registerBindings() {
        AltarBindingRegistry.registerHook(AltarBinding.RS_NETWORK, RSBindingHook.INSTANCE);
    }

    public static void registerCommon() {
        com.huanghuang.rsintegration.voidupgrade.network.VoidUpgradeNetworkHandler.register();
        ResonanceStorageResolvers.register(RSInventoryBridge::resolveResonanceView);
        API.instance().getStorageDiskRegistry().add(
                ResonanceDiskWrapper.FACTORY_ID, new ResonanceDiskFactory());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(PassiveEffectEngine.class);
    }

    public static void registerClientKeyMappings() {
        RSSidePanelClient.registerKeyMappings();
        RSIKeyBindings.registerKeyMappings();
    }

    public static void registerClientEventSubscribers() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                com.huanghuang.rsintegration.mods.rs.RSGridSearchCache.class);
    }

    public static void registerSidePanelCommon() {
        RSSidePanelModule.initCommon();
    }

    public static void registerSidePanelClient() {
        RSSidePanelModule.initClient();
    }

    public static void onPlayerLoggedIn(ServerPlayer player) {
        RSSidePanelNetworkHandler.sendBindingSync(player);
    }

    public static void onPlayerChangedDimension(ServerPlayer player) {
        RSIntegrationNetwork.invalidateNetworkResolution(player.getUUID());
        if (RSSidePanelNetworkHandler.hasListener(player.getUUID())) {
            RSSidePanelNetworkHandler.unregisterListener(player.getUUID());
            INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
            if (network != null) RSSidePanelNetworkHandler.registerListener(player, network);
        }
    }

    public static void clearServerState() {
        com.huanghuang.rsintegration.voidupgrade.network.VoidUpgradeNetworkHandler.clearServerState();
        RSSidePanelNetworkHandler.clearServerState();
    }

}
