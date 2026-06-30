package com.huanghuang.rsintegration.mods.wizards_reborn;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.mods.ModCraftNetworkHandlers;
import com.huanghuang.rsintegration.network.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.WRRecipeHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.function.Supplier;
import java.util.function.Supplier;

public final class WizardsRebornRSModule implements IModIntegration {

    public static final WizardsRebornRSModule INSTANCE = new WizardsRebornRSModule();

    private WizardsRebornRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_WIZARDS_REBORN;
    }

    @Override
    public String modId() {
        return "wizards_reborn";
    }

    @Override
    public void registerModType() {
        ModType.register("wizards_reborn",
                new String[]{"mod.maxbogomol.wizards_reborn."},
                new String[]{"wizards_reborn"},
                new String[]{"crystal_ritual"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.wizards_reborn.WRBatchDelegate"));
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "wizards_reborn", ModType.byId("wizards_reborn"),
                RSIntegrationConfig.ENABLE_WIZARDS_REBORN, List.of(
                "mod.maxbogomol.wizards_reborn.common.block.wissen_crystallizer.WissenCrystallizerBlock",
                "mod.maxbogomol.wizards_reborn.common.block.arcane_iterator.ArcaneIteratorBlock",
                "mod.maxbogomol.wizards_reborn.common.block.arcane_workbench.ArcaneWorkbenchBlock"
        ), "wizards_reborn"));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "wizards_reborn", ModType.byId("wizards_reborn"),
                RSIntegrationConfig.ENABLE_WIZARDS_REBORN, List.of(
                "mod.maxbogomol.wizards_reborn.common.block.crystal.CrystalBlock"
        ), "crystal_ritual"));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new WRRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {
        ModCraftNetworkHandlers.registerWRWand();
    }

    @Override
    public void initCommon() {
        RSIntegrationMod.LOGGER.debug("Wizards Reborn RS module common init done.");
    }

    @Override
    public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> MinecraftForge.EVENT_BUS.register(WRGuiClientEventHandler.class);
    }
}
