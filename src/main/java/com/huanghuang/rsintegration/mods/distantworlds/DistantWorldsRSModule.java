package com.huanghuang.rsintegration.mods.distantworlds;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.mods.distantworlds.client.DistantWorldsClientSetup;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import java.util.function.Supplier;
import java.util.Optional;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;

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
        var channel = NetworkHandler.CHANNEL;
        channel.registerMessage(
                NetworkPacketIds.LITHUM_ALTAR_STATUS_REQUEST,
                LithumAltarStatusRequestPacket.class, LithumAltarStatusRequestPacket::encode,
                LithumAltarStatusRequestPacket::decode, LithumAltarStatusRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(
                NetworkPacketIds.LITHUM_ALTAR_STATUS_SYNC,
                LithumAltarStatusPacket.class, LithumAltarStatusPacket::encode,
                LithumAltarStatusPacket::decode, LithumAltarStatusPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    @Override
    public void initCommon() {
        MinecraftForge.EVENT_BUS.register(LithumCoreInteractionHandler.class);
        MinecraftForge.EVENT_BUS.addListener(
                LithumAltarStatusRequestPacket::onPlayerLogout);
    }

    @Override
    public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> DistantWorldsClientSetup::initClient;
    }
}
