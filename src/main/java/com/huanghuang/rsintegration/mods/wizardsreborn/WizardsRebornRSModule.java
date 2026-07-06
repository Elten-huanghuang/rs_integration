package com.huanghuang.rsintegration.mods.wizardsreborn;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.mods.ModCraftNetworkHandlers;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.WRRecipeHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
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
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.wizardsreborn.WRBatchDelegate"));
        ModType.configureJei("wizards_reborn",
                new String[][]{{"wizards_reborn:wissen_crystallizer", "wissen_crystallizer"}, {"wizards_reborn:arcane_iterator", "arcane_iterator"}, {"wizards_reborn:arcane_workbench", "arcane_workbench"}, {"wizards_reborn:crystal_ritual", "crystal_ritual"}, {"wizards_reborn:crystal_infusion", "crystal_ritual"}},
                new String[][]{{"mod.maxbogomol.wizards_reborn.common.recipe.CrystalInfusion", "crystal_ritual"}, {"mod.maxbogomol.wizards_reborn.common.recipe.CrystalRitual", "crystal_ritual"}, {"mod.maxbogomol.wizards_reborn.common.recipe.ArcaneWorkbench", "arcane_workbench"}, {"mod.maxbogomol.wizards_reborn.common.recipe.ArcaneIterator", "arcane_iterator"}, {"mod.maxbogomol.wizards_reborn.common.recipe.WissenCrystallizer", "wissen_crystallizer"}, {"mod.maxbogomol.wizards_reborn.", "wizards_reborn"}},
                null);
    }

    @Override
    public void registerBindingTargets() {
        // All WR machines are in-world interaction, no container GUI.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "wizards_reborn", ModType.byId("wizards_reborn"),
                RSIntegrationConfig.ENABLE_WIZARDS_REBORN, List.of(
                "mod.maxbogomol.wizards_reborn.common.block.wissen_crystallizer.WissenCrystallizerBlock",
                "mod.maxbogomol.wizards_reborn.common.block.arcane_iterator.ArcaneIteratorBlock",
                "mod.maxbogomol.wizards_reborn.common.block.arcane_workbench.ArcaneWorkbenchBlock"
        ), "wizards_reborn", false));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "wizards_reborn", ModType.byId("wizards_reborn"),
                RSIntegrationConfig.ENABLE_WIZARDS_REBORN, List.of(
                "mod.maxbogomol.wizards_reborn.common.block.crystal.CrystalBlock"
        ), "crystal_ritual", false));
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
