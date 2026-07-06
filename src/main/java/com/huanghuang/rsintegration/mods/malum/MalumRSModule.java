package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.mods.ModCraftNetworkHandlers;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.MalumRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class MalumRSModule implements IModIntegration {

    public static final MalumRSModule INSTANCE = new MalumRSModule();

    private MalumRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_MALUM;
    }

    @Override
    public String modId() {
        return "malum";
    }

    @Override
    public void registerModType() {
        ModType.register("malum_spirit_crucible",
                new String[]{"com.sammy.malum.common.recipe.SpiritFocusingRecipe"},
                new String[]{"spirit_crucible"},
                new String[]{"malum_spirit_crucible"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.malum.MalumSpiritCrucibleBatchDelegate"));
        ModType.register("malum_runic_workbench",
                new String[]{"com.sammy.malum.common.recipe.RunicWorkbenchRecipe"},
                new String[]{"runic_workbench"},
                new String[0],
                MalumRunicWorkbenchBatchDelegate::new);
        ModType.register("malum",
                new String[]{"com.sammy.malum."},
                new String[]{"malum"},
                new String[]{"malum"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.malum.MalumBatchDelegate"));
        ModType.configureJei("malum",
                new String[][]{{"malum:spirit_infusion", "spirit_altar"}, {"malum:spirit_focusing", "spirit_crucible"}},
                new String[][]{{"com.sammy.malum.common.recipe.SpiritFocusingRecipe", "spirit_crucible"}, {"com.sammy.malum.common.recipe.SpiritInfusionRecipe", "spirit_altar"}, {"com.sammy.malum.", "malum"}},
                null);
        ModType.configureJei("malum_runic_workbench",
                new String[][]{{"malum:runeworking", "runic_workbench"}},
                new String[][]{{"com.sammy.malum.common.recipe.RunicWorkbenchRecipe", "malum_runic_workbench"}},
                null);
    }

    @Override
    public void registerBindingTargets() {
        // Spirit Altar is in-world interaction (items placed on altar, spirit pedestals around), no container GUI.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "malum", ModType.byId("malum"), RSIntegrationConfig.ENABLE_MALUM, List.of(
                "com.sammy.malum.common.block.curiosities.spirit_altar.SpiritAltarBlock"
        ), "malum", false));

        // Spirit Crucible Core is in-world multiblock interaction, no container GUI.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "malum", ModType.byId("malum_spirit_crucible"), RSIntegrationConfig.ENABLE_MALUM, List.of(
                "com.sammy.malum.common.block.curiosities.spirit_crucible.SpiritCrucibleCoreBlock"
        ), "malum_spirit_crucible", false));
        // Component blocks are multi-block parts with no GUI of their own.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "malum", ModType.byId("malum_spirit_crucible"), RSIntegrationConfig.ENABLE_MALUM, List.of(
                "com.sammy.malum.common.block.curiosities.spirit_crucible.SpiritCrucibleComponentBlock"
        ), "malum_spirit_crucible_component", false));

        // Runic Workbench — instant-craft in-world interaction (primary on table, secondary in hand right-click)
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "malum", ModType.byId("malum_runic_workbench"), RSIntegrationConfig.ENABLE_MALUM, List.of(
                "com.sammy.malum.common.block.curiosities.runic_workbench.RunicWorkbenchBlock"
        ), "malum_runic_workbench", false));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new MalumRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {
        ModCraftNetworkHandlers.registerMalum();
    }

    @Override
    public void initCommon() {
        RSIntegrationMod.LOGGER.debug("Malum RS module common init done.");
    }
}
