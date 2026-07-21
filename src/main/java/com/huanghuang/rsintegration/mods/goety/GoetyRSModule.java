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
                new String[]{"goety"}, new String[]{"goety_cursed_infuser"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.goety.CursedInfuserBatchDelegate"));
        ModType.configureJei("goety_cursed_infuser",
                new String[][]{{"goety:cursed_infuser"}},
                new String[][]{{"com.Polarice3.Goety.common.crafting.CursedInfuserRecipes", "goety:cursed_infuser"}}, null);
        ModType.register("goety",
                new String[]{
                        "com.Polarice3.Goety.common.crafting.RitualRecipe",
                        "com.Polarice3.Goety.common.crafting.BrazierRecipe"},
                new String[]{"goety"},
                new String[]{"goety", "goety_altar", "goety_component"},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.goety.GoetyBatchDelegate"));
        ModType.configureJei("goety",
                new String[][]{{"goety:brazier"}},
                new String[][]{
                        {"com.Polarice3.Goety.common.crafting.RitualRecipe", "goety"},
                        {"com.Polarice3.Goety.common.crafting.BrazierRecipe", "goety"}},
                null);
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "goety", ModType.byId("goety_cursed_infuser"), RSIntegrationConfig.ENABLE_GOETY,
                List.of("com.Polarice3.Goety.common.blocks.CursedInfuserBlock"),
                List.of("goety:cursed_infuser"), "goety_cursed_infuser", false));
        // NecroBrazier is an in-world ritual block, no container GUI.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "goety", ModType.byId("goety"), RSIntegrationConfig.ENABLE_GOETY, List.of(
                "com.Polarice3.Goety.common.blocks.NecroBrazierBlock"
        ), "goety", false));
        // Dark Altar is in-world interaction (place items on top, wand-trigger), no container GUI.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "goety", ModType.byId("goety"), RSIntegrationConfig.ENABLE_GOETY, List.of(
                "com.Polarice3.Goety.common.blocks.DarkAltarBlock"
        ), "goety_altar", false));
        // Multi-block components with no GUI of their own.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "goety", ModType.byId("goety"), RSIntegrationConfig.ENABLE_GOETY, List.of(
                "com.Polarice3.Goety.common.blocks.CursedCageBlock",
                "com.Polarice3.Goety.common.blocks.SoulCandlestickBlock"
        ), "goety_component", false));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new com.huanghuang.rsintegration.recipe.CursedInfuserRecipeHandler());
        ModRecipeHandlers.register(new GoetyRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {
        GoetyRSNetworkHandler.register();
    }

    @Override
    public void initCommon() {
        RSIntegrationMod.LOGGER.debug("Goety RS module common init done.");
    }

    @Override
    public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> MinecraftForge.EVENT_BUS.register(GoetyGuiClientEventHandler.class);
    }

    public void onJeiRuntimeAvailable(IJeiRuntime jeiRuntime) {
        // no-op: Goety does not require JEI runtime integration
    }

    public void onJeiRuntimeUnavailable() {
        RSClientAvailabilityCache.clear();
    }

    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        // no-op: Goety does not use recipe transfer handlers
    }
}
