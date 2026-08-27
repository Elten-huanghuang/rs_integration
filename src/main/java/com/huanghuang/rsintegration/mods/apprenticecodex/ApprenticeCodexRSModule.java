package com.huanghuang.rsintegration.mods.apprenticecodex;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class ApprenticeCodexRSModule implements IModIntegration {
    public static final ApprenticeCodexRSModule INSTANCE = new ApprenticeCodexRSModule();
    public static final String ESSENCE_SMOKER_TYPE = "apprenticecodex_essence_smoker";
    public static final String SPELLCASTER_WORKBENCH_TYPE = "apprenticecodex_spellcaster_workbench";
    static final String ESSENCE_RECIPE =
            "jp.aquafactory.apprenticecodex.recipe.essencesmoker.EssenceSmokerRecipe";
    static final String WORKBENCH_RECIPE =
            "jp.aquafactory.apprenticecodex.recipe.spellcasterworkbench.SpellcasterWorkbenchRecipe";

    private ApprenticeCodexRSModule() {}

    @Override public ForgeConfigSpec.BooleanValue configFlag() { return RSIntegrationConfig.ENABLE_APPRENTICE_CODEX; }
    @Override public String modId() { return ModIds.APPRENTICE_CODEX; }

    @Override
    public void registerModType() {
        ModType.register(ESSENCE_SMOKER_TYPE, new String[]{ESSENCE_RECIPE},
                new String[]{"essence_smoker"}, new String[]{ESSENCE_SMOKER_TYPE},
                ModType.delegateSupplier(ApprenticeCodexEssenceSmokerBatchDelegate.class.getName()))
                .requireFlatExecution(
                        "Essence Smoker consumes one catalyst per eight-material physical batch");
        ModType.configureJei(ESSENCE_SMOKER_TYPE,
                new String[][]{{"apprenticecodex:essence_smoker", ESSENCE_SMOKER_TYPE}},
                new String[][]{{ESSENCE_RECIPE, ESSENCE_SMOKER_TYPE}},
                "gui.rs_integration.jei.apprenticecodex_essence_smoker");

        ModType.register(SPELLCASTER_WORKBENCH_TYPE, new String[]{WORKBENCH_RECIPE},
                new String[]{"spellcaster_workbench"}, new String[]{SPELLCASTER_WORKBENCH_TYPE},
                ModType.delegateSupplier(ApprenticeCodexWorkbenchBatchDelegate.class.getName()));
        ModType.configureJei(SPELLCASTER_WORKBENCH_TYPE,
                new String[][]{{"apprenticecodex:spellcaster_workbench", SPELLCASTER_WORKBENCH_TYPE}},
                new String[][]{{WORKBENCH_RECIPE, SPELLCASTER_WORKBENCH_TYPE}},
                "gui.rs_integration.jei.apprenticecodex_spellcaster_workbench");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.APPRENTICE_CODEX, ModType.byId(ESSENCE_SMOKER_TYPE),
                RSIntegrationConfig.ENABLE_APPRENTICE_CODEX,
                List.of("jp.aquafactory.apprenticecodex.block.essencesmoker.EssenceSmoker"),
                List.of("apprenticecodex:essence_smoker"), ESSENCE_SMOKER_TYPE, true));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.APPRENTICE_CODEX, ModType.byId(SPELLCASTER_WORKBENCH_TYPE),
                RSIntegrationConfig.ENABLE_APPRENTICE_CODEX,
                List.of("jp.aquafactory.apprenticecodex.block.spellcasterworkbench.SpellcasterWorkbench"),
                List.of("apprenticecodex:spellcaster_workbench"), SPELLCASTER_WORKBENCH_TYPE, true));
    }

    @Override public void registerRecipeHandler() {
        ModRecipeHandlers.register(ApprenticeCodexRecipeHandler.essenceSmoker());
        ModRecipeHandlers.register(ApprenticeCodexRecipeHandler.spellcasterWorkbench());
    }
    @Override public void registerNetworkPackets() {}
    @Override public void initCommon() {}
}
