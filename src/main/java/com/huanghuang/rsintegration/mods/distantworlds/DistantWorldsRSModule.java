package com.huanghuang.rsintegration.mods.distantworlds;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class DistantWorldsRSModule implements IModIntegration {
    public static final DistantWorldsRSModule INSTANCE = new DistantWorldsRSModule();
    private DistantWorldsRSModule() {}

    @Override public ForgeConfigSpec.BooleanValue configFlag() { return RSIntegrationConfig.ENABLE_DISTANT_WORLDS; }
    @Override public String modId() { return "distant_worlds"; }

    @Override
    public void registerModType() {
        ModType.register(LithumAltarRecipeResolver.TYPE_ID,
                new String[]{LithumAltarRecipeWrapper.class.getName()},
                new String[]{"lithum_core", "lithum_altar"},
                new String[]{LithumAltarRecipeResolver.TYPE_ID},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.distantworlds.LithumAltarBatchDelegate"));
        ModType.configureJei(LithumAltarRecipeResolver.TYPE_ID,
                new String[][]{{"rs_integration:lithum_altar_firon", LithumAltarRecipeResolver.TYPE_ID}},
                new String[][]{{LithumAltarRecipeWrapper.class.getName(), LithumAltarRecipeResolver.TYPE_ID}},
                "gui.rs_integration.jei.distant_worlds_lithum_altar");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                modId(), ModType.byId(LithumAltarRecipeResolver.TYPE_ID),
                RSIntegrationConfig.ENABLE_DISTANT_WORLDS,
                List.of("net.mcreator.distantworlds.block.LithumCoreBlock"),
                LithumAltarRecipeResolver.TYPE_ID, true));
    }

    @Override public void registerRecipeHandler() { ModRecipeHandlers.register(new LithumAltarRecipeHandler()); }
    @Override
    public void registerNetworkPackets() {
        var channel = com.huanghuang.rsintegration.network.packet.NetworkHandler.CHANNEL;
        channel.registerMessage(
                com.huanghuang.rsintegration.network.packet.NetworkPacketIds.LITHUM_ALTAR_STATUS_REQUEST,
                LithumAltarStatusRequestPacket.class, LithumAltarStatusRequestPacket::encode,
                LithumAltarStatusRequestPacket::decode, LithumAltarStatusRequestPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(
                com.huanghuang.rsintegration.network.packet.NetworkPacketIds.LITHUM_ALTAR_STATUS_SYNC,
                LithumAltarStatusPacket.class, LithumAltarStatusPacket::encode,
                LithumAltarStatusPacket::decode, LithumAltarStatusPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
    }
    @Override
    public void initCommon() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(LithumCoreInteractionHandler.class);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                LithumAltarStatusRequestPacket::onPlayerLogout);
    }

    @Override
    public java.util.function.Supplier<net.minecraftforge.fml.DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> com.huanghuang.rsintegration.mods.distantworlds.client.DistantWorldsClientSetup::initClient;
    }
}
