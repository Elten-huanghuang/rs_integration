package com.huanghuang.rsintegration.mods.pmmo;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class PmmoRSModule implements IModIntegration {
    public static final PmmoRSModule INSTANCE = new PmmoRSModule();
    public static final String TYPE_ID = "pmmo_salvage";

    private PmmoRSModule() {}

    @Override public ForgeConfigSpec.BooleanValue configFlag() { return RSIntegrationConfig.ENABLE_PMMO; }
    @Override public String modId() { return ModIds.PMMO; }

    @Override
    public void registerModType() {
        ModType.register(TYPE_ID,
                new String[]{PmmoSalvageRecipeWrapper.class.getName()},
                new String[]{TYPE_ID}, new String[]{TYPE_ID},
                PmmoSalvageBatchDelegate::new)
                .requireFlatExecution("salvage output is probabilistic and each selected count is one attempt");
        ModType.configureJei(TYPE_ID,
                new String[][]{{"rs_integration:pmmo_salvage", TYPE_ID}},
                new String[][]{{"com.huanghuang.rsintegration.mods.pmmo.client.PmmoSalvageRecipe", TYPE_ID}},
                "gui.rs_integration.jei.pmmo_salvage_craft");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.PMMO, ModType.byId(TYPE_ID), RSIntegrationConfig.ENABLE_PMMO,
                List.of(), List.of(), TYPE_ID, false));
    }

    @Override public void registerRecipeHandler() {
        ModRecipeHandlers.register(new PmmoSalvageRecipeHandler());
    }
    @Override public void registerNetworkPackets() {}
    @Override public void initCommon() { PmmoSalvageCatalog.refresh(); }
}
