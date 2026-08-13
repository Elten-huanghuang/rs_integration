package com.huanghuang.rsintegration.mods.isscsw;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class IssCswRSModule implements IModIntegration {
    public static final IssCswRSModule INSTANCE = new IssCswRSModule();
    public static final String SPELL_FORGE_TYPE = "iss_csw_spell_forge";
    static final String RECIPE_CLASS = "org.xszb.interlace_spellweaves.recipe.SpellMixRecipe";

    private IssCswRSModule() {}

    @Override public ForgeConfigSpec.BooleanValue configFlag() { return RSIntegrationConfig.ENABLE_ISS_CSW; }
    @Override public String modId() { return ModIds.ISS_CSW; }

    @Override public void registerModType() {
        ModType.register(SPELL_FORGE_TYPE, new String[]{RECIPE_CLASS},
                new String[]{"spell_forge"}, new String[]{SPELL_FORGE_TYPE},
                ModType.delegateSupplier(IssCswSpellForgeBatchDelegate.class.getName()));
        ModType.configureJei(SPELL_FORGE_TYPE,
                new String[][]{{"iss_csw:spell_forge", SPELL_FORGE_TYPE}},
                new String[][]{{RECIPE_CLASS, SPELL_FORGE_TYPE}},
                "gui.rs_integration.jei.iss_csw_spell_forge");
    }

    @Override public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.ISS_CSW, ModType.byId(SPELL_FORGE_TYPE), RSIntegrationConfig.ENABLE_ISS_CSW,
                List.of("org.xszb.interlace_spellweaves.block.spell_forge.SpellForgeBlock"),
                List.of("iss_csw:spell_forge"), SPELL_FORGE_TYPE, true));
    }

    @Override public void registerRecipeHandler() { ModRecipeHandlers.register(new IssCswRecipeHandler()); }
    @Override public void registerNetworkPackets() {}
    @Override public void initCommon() {}
}
