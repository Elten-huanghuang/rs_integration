package com.huanghuang.rsintegration.mods.immortalers_delight;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.EnchantalCoolerRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.function.Supplier;

public final class ImmortalersDelightRSModule implements IModIntegration {

    public static final ImmortalersDelightRSModule INSTANCE = new ImmortalersDelightRSModule();

    private ImmortalersDelightRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_IMMORTERS_DELIGHT;
    }

    @Override
    public String modId() {
        return "immortalers_delight";
    }

    @Override
    public void registerModType() {
        ModType.register("immortalers_delight",
                new String[]{"com.renyigesai.immortalers_delight.recipe.EnchantalCoolerRecipe"},
                new String[]{"enchantal_cooler"},
                new String[0],
                EnchantalCoolerBatchDelegate::new);
        ModType.configureJei("immortalers_delight",
                new String[][]{{"immortalers_delight:enchantal_cooler"}},
                new String[][]{{"com.renyigesai.immortalers_delight.recipe.EnchantalCoolerRecipe", "immortalers_delight"}},
                "gui.rs_integration.jei.immortalers_cooler_craft");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "immortalers_delight", ModType.byId("immortalers_delight"),
                RSIntegrationConfig.ENABLE_IMMORTERS_DELIGHT,
                List.of("com.renyigesai.immortalers_delight.block.enchantal_cooler.EnchantalCoolerBlock"),
                "immortalers_delight"
        ));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new EnchantalCoolerRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {}

    @Override
    public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> {};
    }
}
