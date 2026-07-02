package com.huanghuang.rsintegration.mods.aether;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.AetherRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;
import java.util.function.Supplier;

public final class AetherRSModule implements IModIntegration {

    public static final AetherRSModule INSTANCE = new AetherRSModule();

    private AetherRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_AETHER;
    }

    @Override
    public String modId() {
        return "aether";
    }

    @Override
    public void registerModType() {
        ModType.register("aether",
                new String[]{"com.aetherteam.aether.recipe.recipes.item."},
                new String[]{"freezer", "incubator", "altar"},
                new String[0],
                AetherFurnaceBatchDelegate::new);
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "aether", ModType.byId("aether"),
                RSIntegrationConfig.ENABLE_AETHER,
                List.of("com.aetherteam.aether.block.utility.FreezerBlock"),
                "aether_freezer"
        ));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "aether", ModType.byId("aether"),
                RSIntegrationConfig.ENABLE_AETHER,
                List.of("com.aetherteam.aether.block.utility.IncubatorBlock"),
                "aether_incubator"
        ));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "aether", ModType.byId("aether"),
                RSIntegrationConfig.ENABLE_AETHER,
                List.of("com.aetherteam.aether.block.utility.AltarBlock"),
                "aether_altar"
        ));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new AetherRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {}

    @Override
    public Supplier<net.minecraftforge.fml.DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> {};
    }
}
