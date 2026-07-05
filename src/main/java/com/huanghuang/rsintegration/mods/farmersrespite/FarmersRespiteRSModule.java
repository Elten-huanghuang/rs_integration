package com.huanghuang.rsintegration.mods.farmersrespite;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.BindingEventHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.function.Supplier;

public final class FarmersRespiteRSModule implements IModIntegration {

    public static final FarmersRespiteRSModule INSTANCE = new FarmersRespiteRSModule();

    private FarmersRespiteRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_FARMERSRESPITE;
    }

    @Override
    public String modId() {
        return "farmersrespite";
    }

    @Override
    public void registerModType() {
        ModType.register("farmersrespite_kettle",
                new String[]{"umpaz.farmersrespite.common.crafting.KettleRecipe"},
                new String[]{"kettle"},
                new String[]{"farmersrespite_kettle"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.farmersrespite.kettle.FRKettleBatchDelegate"));
        ModType.configureJei("farmersrespite_kettle",
                new String[][]{{"farmersrespite:kettle"}},
                new String[][]{{"umpaz.farmersrespite.common.crafting.KettleRecipe", "farmersrespite_kettle"}},
                "gui.rs_integration.jei.fr_kettle_craft");

        // General fallback
        ModType.register("farmersrespite",
                new String[]{"umpaz.farmersrespite."},
                new String[]{"farmersrespite"},
                new String[0],
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.farmersrespite.kettle.FRKettleBatchDelegate"));
        ModType.configureJei("farmersrespite",
                new String[][]{{"farmersrespite:kettle", "farmersrespite_kettle"}},
                new String[][]{{"umpaz.farmersrespite.", "farmersrespite"}},
                null);
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "farmersrespite", ModType.byId("farmersrespite_kettle"),
                RSIntegrationConfig.ENABLE_FARMERSRESPITE,
                List.of("umpaz.farmersrespite.common.block.KettleBlock"),
                "farmersrespite_kettle", true
        ));
    }

    @Override
    public void registerRecipeHandler() {}

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {}

    @Override
    public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> {};
    }
}
