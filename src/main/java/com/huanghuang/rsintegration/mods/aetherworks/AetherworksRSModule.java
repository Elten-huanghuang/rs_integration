package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.mods.aetherworks.client.AetherworksClientSetup;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.AetherworksRecipeHandler;
import com.huanghuang.rsintegration.recipe.AetherworksToolStationRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;

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
        ModType.register(ModIds.ID_AETHERWORKS_ANVIL,
                new String[]{"net.sirplop.aetherworks.recipe.AetheriumAnvilRecipe"},
                new String[]{"aetherworks", "aetherium", "anvil"},
                new String[0],
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.aetherworks.AetherworksBatchDelegate"));
        ModType.configureJei(ModIds.ID_AETHERWORKS_ANVIL,
                new String[][]{{"aetherworks:anvil", "aetherworks"}},
                new String[][]{{"net.sirplop.aetherworks.", "aetherworks"}},
                null);

        // Forge Tool Station
        ModType.register(ModIds.ID_AETHERWORKS_TOOL_STATION,
                new String[]{"net.sirplop.aetherworks.recipe.ToolStationRecipe"},
                new String[]{"tool_station"},
                new String[0],
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.aetherworks.AetherworksToolStationBatchDelegate"));
        ModType.configureJei(ModIds.ID_AETHERWORKS_TOOL_STATION,
                new String[][]{{"aetherworks:tool_station", "aetherworks"}},
                new String[][]{{"net.sirplop.aetherworks.", "aetherworks"}},
                null);
    }

    @Override
    public void registerBindingTargets() {
        // Aetherium Anvil: hammer right-click interaction on in-world items, no container GUI.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "aetherworks", ModType.byId(ModIds.ID_AETHERWORKS_ANVIL),
                RSIntegrationConfig.ENABLE_AETHERWORKS,
                List.of("net.sirplop.aetherworks.block.forge.AetheriumAnvilBlock"),
                "aetherworks", false
        ));
        // Forge Tool Station
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "aetherworks", ModType.byId(ModIds.ID_AETHERWORKS_TOOL_STATION),
                RSIntegrationConfig.ENABLE_AETHERWORKS,
                List.of("net.sirplop.aetherworks.block.forge.ForgeToolStation"),
                "aetherworks", false
        ));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new AetherworksRecipeHandler());
        ModRecipeHandlers.register(new AetherworksToolStationRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {
        RSIntegrationMod.LOGGER.debug("[RSI-Aetherworks] Common init done.");
    }

    @Override
    public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> AetherworksClientSetup.initClient();
    }
}
