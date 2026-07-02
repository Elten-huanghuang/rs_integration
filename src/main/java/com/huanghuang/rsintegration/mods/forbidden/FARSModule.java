package com.huanghuang.rsintegration.mods.forbidden;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.mods.ModCraftNetworkHandlers;
import com.huanghuang.rsintegration.network.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.FaRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class FaRSModule implements IModIntegration {

    public static final FaRSModule INSTANCE = new FaRSModule();

    private FaRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_FORBIDDEN_ARCANUS;
    }

    @Override
    public String modId() {
        return "forbidden_arcanus";
    }

    @Override
    public void registerModType() {
        ModType.register("forbidden_arcanus",
                new String[]{"com.stal111.forbidden_arcanus."},
                new String[]{"forbidden_arcanus"},
                new String[0],
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.forbidden.FaBatchDelegate"));
    }

    @Override
    public void registerBindingTargets() {
        // Hephaestus Forge: essence status panel only, no item I/O container GUI. All materials placed in-world.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "forbidden_arcanus", ModType.byId("forbidden_arcanus"),
                RSIntegrationConfig.ENABLE_FORBIDDEN_ARCANUS, List.of(
                "com.stal111.forbidden_arcanus.common.block.HephaestusForgeBlock"
        ), "forbidden_arcanus", false));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new FaRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {
        ModCraftNetworkHandlers.registerFa();
    }

    @Override
    public void initCommon() {
        RSIntegrationMod.LOGGER.debug("Forbidden & Arcanus RS module common init done.");
    }
}
