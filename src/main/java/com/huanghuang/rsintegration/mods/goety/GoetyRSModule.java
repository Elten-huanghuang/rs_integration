package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.GoetyRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.function.Supplier;

public final class GoetyRSModule implements IModIntegration {

    public static final GoetyRSModule INSTANCE = new GoetyRSModule();
    public static final String BRAZIER_TYPE_ID = "goety_brazier";
    public static final List<String> CURSED_INFUSER_BLOCK_CLASSES = List.of(
            "com.Polarice3.Goety.common.blocks.CursedInfuserBlock",
            "com.Polarice3.Goety.common.blocks.GrimInfuserBlock",
            "com.k1sak1.goetyawaken.common.blocks.DarkMenderBlock");
    public static final List<String> CURSED_INFUSER_BLOCK_IDS = List.of(
            "goety:cursed_infuser",
            "goety:grim_infuser",
            "goetyawaken:dark_mender");

    private GoetyRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_GOETY;
    }

    @Override
    public String modId() {
        return "goety";
    }

    @Override
    public void registerModType() {
        ModType.register("goety_cursed_infuser",
                new String[]{"com.Polarice3.Goety.common.crafting.CursedInfuserRecipes"},
                new String[]{"cursed_infuser", "grim_infuser", "dark_mender"},
                new String[]{"goety_cursed_infuser"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.goety.CursedInfuserBatchDelegate"));
        ModType.configureJei("goety_cursed_infuser",
                new String[][]{{"goety:cursed_infuser"}},
                new String[][]{{"com.Polarice3.Goety.common.crafting.CursedInfuserRecipes", "goety_cursed_infuser"}}, null);
        ModType.register("goety",
                new String[]{"com.Polarice3.Goety.common.crafting.RitualRecipe"},
                new String[]{"dark_altar"},
                new String[]{"goety_altar"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.goety.GoetyBatchDelegate"));
        ModType.configureJei("goety",
                null,
                new String[][]{{"com.Polarice3.Goety.common.crafting.RitualRecipe", "goety_altar"}},
                null);
        ModType.register(BRAZIER_TYPE_ID,
                new String[]{"com.Polarice3.Goety.common.crafting.BrazierRecipe"},
                new String[]{"necro_brazier"},
                new String[]{"goety"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.goety.GoetyBatchDelegate"));
        ModType.configureJei(BRAZIER_TYPE_ID,
                new String[][]{{"goety:brazier", "goety"}},
                new String[][]{{"com.Polarice3.Goety.common.crafting.BrazierRecipe", "goety"}},
                "gui.rs_integration.jei.goety_brazier_craft");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "goety", ModType.CUSTOM_GUI, RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS,
                List.of(), List.of("goety:dark_anvil"), "custom_gui", true));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "goety", ModType.byId("goety_cursed_infuser"), RSIntegrationConfig.ENABLE_GOETY,
                CURSED_INFUSER_BLOCK_CLASSES, CURSED_INFUSER_BLOCK_IDS,
                "goety_cursed_infuser", false));
        // NecroBrazier is an in-world ritual block, no container GUI.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "goety", ModType.byId(BRAZIER_TYPE_ID), RSIntegrationConfig.ENABLE_GOETY, List.of(
                "com.Polarice3.Goety.common.blocks.NecroBrazierBlock"
        ), "goety", false));
        // Dark Altar is in-world interaction (place items on top, wand-trigger), no container GUI.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "goety", ModType.byId("goety"), RSIntegrationConfig.ENABLE_GOETY, List.of(
                "com.Polarice3.Goety.common.blocks.DarkAltarBlock"
        ), "goety_altar", false));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new com.huanghuang.rsintegration.recipe.CursedInfuserRecipeHandler());
        ModRecipeHandlers.register(GoetyRecipeHandler.ritual());
        ModRecipeHandlers.register(GoetyRecipeHandler.brazier());
    }

    @Override
    public void initCommon() {
        RSIntegrationMod.LOGGER.debug("Goety RS module common init done.");
    }

    @Override
    public void registerNetworkPackets() {
        // Material status packets are registered by the common crafting channel.
    }

    @Override
    public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> MinecraftForge.EVENT_BUS.register(GoetyGuiClientEventHandler.class);
    }

    public void onJeiRuntimeAvailable(IJeiRuntime jeiRuntime) {
        // no-op: Goety does not require JEI runtime integration
    }

    public void onJeiRuntimeUnavailable() {
        // Common recipe availability is cleared by the client lifecycle.
    }

    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        // no-op: Goety does not use recipe transfer handlers
    }
}
