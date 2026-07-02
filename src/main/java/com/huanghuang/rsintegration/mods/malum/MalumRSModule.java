package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.mods.ModCraftNetworkHandlers;
import com.huanghuang.rsintegration.network.BindingEventHandler;
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
        ModType.register("malum",
                new String[]{"com.sammy.malum."},
                new String[]{"malum"},
                new String[0],
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.malum.MalumBatchDelegate"));
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
