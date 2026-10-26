package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.mods.aetherworks.client.AetherworksClientSetup;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.mods.aetherworks.AetherworksRecipeHandler;
import com.huanghuang.rsintegration.mods.aetherworks.AetherworksToolStationRecipeHandler;
import com.huanghuang.rsintegration.mods.aetherworks.AetherworksMetalFormerRecipeHandler;
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

        // 金属成型台与锻造炉共享热量、余烬及外接流体，使用串行机器执行。
        ModType.register(ModIds.ID_AETHERWORKS_METAL_FORMER,
                new String[]{"net.sirplop.aetherworks.recipe.MetalFormerRecipe"},
                new String[]{"forge_metal_former"},
                new String[]{ModIds.ID_AETHERWORKS_METAL_FORMER},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.aetherworks.AetherworksMetalFormerBatchDelegate"))
                .rejectPureProjection("金属成型台与锻造炉共享能量和流体，输入与产物共用槽位");
        ModType.configureJei(ModIds.ID_AETHERWORKS_METAL_FORMER,
                new String[][]{{"aetherworks:metal_forming", ModIds.ID_AETHERWORKS_METAL_FORMER}},
                new String[][]{{"net.sirplop.aetherworks.recipe.MetalFormerRecipe", ModIds.ID_AETHERWORKS_METAL_FORMER}},
                "gui.rs_integration.jei.aetherworks_metal_former_craft");

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
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.AETHERWORKS, ModType.byId(ModIds.ID_AETHERWORKS_METAL_FORMER),
                RSIntegrationConfig.ENABLE_AETHERWORKS,
                List.of("net.sirplop.aetherworks.block.forge.MetalFormerBlock"),
                ModIds.ID_AETHERWORKS_METAL_FORMER, false
        ));
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
        ModRecipeHandlers.register(new AetherworksMetalFormerRecipeHandler());
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
