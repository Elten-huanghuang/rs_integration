package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.AetherworksRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;
import java.util.function.Supplier;

public final class AetherworksRSModule implements IModIntegration {

    public static final AetherworksRSModule INSTANCE = new AetherworksRSModule();

    private AetherworksRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_AETHERWORKS;
    }

    @Override
    public String modId() {
        return "aetherworks";
    }

    @Override
    public void registerModType() {
        ModType.register("aetherworks_anvil",
                new String[]{"net.sirplop.aetherworks."},
                new String[]{"aetherworks", "aetherium", "anvil"},
                new String[0],
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.aetherworks.AetherworksBatchDelegate"));
        ModType.configureJei("aetherworks_anvil",
                new String[][]{{"aetherworks:anvil", "aetherworks"}},
                new String[][]{{"net.sirplop.aetherworks.", "aetherworks"}},
                null);
    }

    @Override
    public void registerBindingTargets() {
        // Aetherium Anvil: hammer right-click interaction on in-world items, no container GUI.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "aetherworks", ModType.byId("aetherworks_anvil"),
                RSIntegrationConfig.ENABLE_AETHERWORKS,
                List.of("net.sirplop.aetherworks.block.forge.AetheriumAnvilBlock"),
                "aetherworks", false
        ));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new AetherworksRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {
        RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] Common init done.");
    }

    @Override
    public Supplier<net.minecraftforge.fml.DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> com.huanghuang.rsintegration.mods.aetherworks.client.AetherworksClientSetup
                .initClient();
    }
}
