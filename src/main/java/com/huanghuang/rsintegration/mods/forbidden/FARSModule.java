package com.huanghuang.rsintegration.mods.forbidden;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.mods.ModCraftNetworkHandlers;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ClibanoRecipeHandler;
import com.huanghuang.rsintegration.recipe.FaRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
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
        ModType.configureJei("forbidden_arcanus",
                new String[][]{{"forbidden_arcanus:hephaestus_smithing", "hephaestus_forge"}, {"forbidden_arcanus:hephaestus_forge_upgrading", "hephaestus_forge"}, {"forbidden_arcanus:hephaestus_forge", "hephaestus_forge"}},
                new String[][]{{"com.stal111.forbidden_arcanus.", "hephaestus_forge"}},
                null);

        ModType.register(ModIds.ID_FA_CLIBANO,
                new String[]{"com.stal111.forbidden_arcanus.common.recipe.ClibanoRecipe"},
                new String[]{"clibano"},
                new String[]{ModIds.ID_FA_CLIBANO},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.forbidden.ClibanoBatchDelegate"));
        ModType.configureJei(ModIds.ID_FA_CLIBANO,
                new String[][]{{"forbidden_arcanus:clibano_combustion", ModIds.ID_FA_CLIBANO}},
                new String[][]{{"com.stal111.forbidden_arcanus.common.recipe.ClibanoRecipe", ModIds.ID_FA_CLIBANO}},
                "gui.rs_integration.jei.clibano_craft");
    }

    @Override
    public void registerBindingTargets() {
        // Hephaestus Forge: has an interactive GUI (essence, rituals, tier info)
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "forbidden_arcanus", ModType.byId("forbidden_arcanus"),
                RSIntegrationConfig.ENABLE_FORBIDDEN_ARCANUS, List.of(
                "com.stal111.forbidden_arcanus.common.block.HephaestusForgeBlock"
        ), "forbidden_arcanus", true));

        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "forbidden_arcanus", ModType.byId(ModIds.ID_FA_CLIBANO),
                RSIntegrationConfig.ENABLE_FORBIDDEN_ARCANUS, List.of(
                "com.stal111.forbidden_arcanus.common.block.ClibanoCenterBlock",
                "com.stal111.forbidden_arcanus.common.block.ClibanoCornerBlock",
                "com.stal111.forbidden_arcanus.common.block.ClibanoHorizontalSideBlock",
                "com.stal111.forbidden_arcanus.common.block.ClibanoVerticalSideBlock",
                "com.stal111.forbidden_arcanus.common.block.ClibanoMainPartBlock"
        ), ModIds.ID_FA_CLIBANO, true));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new FaRecipeHandler());
        ModRecipeHandlers.register(new ClibanoRecipeHandler());
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
