package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.function.Supplier;

public final class IronSpellBooksRSModule implements IModIntegration {
    public static final IronSpellBooksRSModule INSTANCE = new IronSpellBooksRSModule();
    public static final String SCROLL_FORGE_TYPE = "irons_spellbooks_scroll_forge";
    public static final String ARCANE_ANVIL_TYPE = "irons_spellbooks_arcane_anvil";

    private IronSpellBooksRSModule() {}

    @Override public ForgeConfigSpec.BooleanValue configFlag() { return RSIntegrationConfig.ENABLE_IRONS_SPELLBOOKS; }
    @Override public String modId() { return "irons_spellbooks"; }

    @Override
    public void registerModType() {
        ModType.register(SCROLL_FORGE_TYPE,
                new String[]{IronSpellBooksRecipe.class.getName()},
                new String[]{"scroll_forge", "scrollforge"}, new String[]{SCROLL_FORGE_TYPE},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksBatchDelegate"));
        ModType.register(ARCANE_ANVIL_TYPE,
                new String[]{IronSpellBooksRecipe.class.getName()},
                new String[]{"arcane_anvil", "arcaneanvil"}, new String[]{ARCANE_ANVIL_TYPE},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksBatchDelegate"));
        ModType.configureJei(SCROLL_FORGE_TYPE,
                new String[][]{{"irons_spellbooks:scroll_forge", SCROLL_FORGE_TYPE}},
                new String[][]{{"io.redspace.ironsspellbooks.jei.ScrollForgeRecipe", SCROLL_FORGE_TYPE}},
                "gui.rs_integration.jei.irons_spellbooks_scroll_forge");
        ModType.configureJei(ARCANE_ANVIL_TYPE,
                new String[][]{{"irons_spellbooks:arcane_anvil", ARCANE_ANVIL_TYPE}},
                new String[][]{
                        {"io.redspace.ironsspellbooks.jei.ArcaneAnvilRecipe", ARCANE_ANVIL_TYPE},
                        {"io.redspace.ironsspellbooks.jei.ArcaneAnvilJeiRecipe", ARCANE_ANVIL_TYPE}},
                "gui.rs_integration.jei.irons_spellbooks_arcane_anvil");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "irons_spellbooks", ModType.byId(SCROLL_FORGE_TYPE), RSIntegrationConfig.ENABLE_IRONS_SPELLBOOKS,
                List.of("io.redspace.ironsspellbooks.block.scroll_forge.ScrollForgeBlock"),
                List.of("irons_spellbooks:scroll_forge"), SCROLL_FORGE_TYPE, true));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "irons_spellbooks", ModType.CUSTOM_GUI, RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS,
                List.of(), List.of("irons_spellbooks:inscription_table"), "custom_gui", true));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "irons_spellbooks", ModType.byId(ARCANE_ANVIL_TYPE), RSIntegrationConfig.ENABLE_IRONS_SPELLBOOKS,
                List.of("io.redspace.ironsspellbooks.block.arcane_anvil.ArcaneAnvilBlock"),
                List.of("irons_spellbooks:arcane_anvil"), ARCANE_ANVIL_TYPE, true));
    }

    @Override public void registerRecipeHandler() { ModRecipeHandlers.register(new IronSpellBooksRecipeHandler()); }
    @Override public void registerNetworkPackets() {}
    @Override public void initCommon() {}
    @Override public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() { return () -> () -> {}; }
}
