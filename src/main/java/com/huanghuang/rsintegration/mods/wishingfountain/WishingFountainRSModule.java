package com.huanghuang.rsintegration.mods.wishingfountain;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class WishingFountainRSModule implements IModIntegration {
    public static final WishingFountainRSModule INSTANCE = new WishingFountainRSModule();
    public static final String TYPE_ID = "wishing_fountain";

    private WishingFountainRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_WISHING_FOUNTAIN;
    }

    @Override
    public String modId() {
        return ModIds.WISHING_FOUNTAIN;
    }

    @Override
    public void registerModType() {
        ModType.register(TYPE_ID,
                new String[]{WishingFountainRecipeHandler.RECIPE_CLASS},
                new String[]{TYPE_ID}, new String[]{TYPE_ID},
                ModType.delegateSupplier(
                        "com.huanghuang.rsintegration.mods.wishingfountain.WishingFountainBatchDelegate"))
                .confirmGraphExecution(
                        "formed fountain inputs and synchronous output capture are transactionally owned");
        ModType.configureJei(TYPE_ID,
                new String[][]{{"wishing_fountain:wishing_fountain", TYPE_ID}},
                new String[][]{
                        {WishingFountainRecipeHandler.RECIPE_CLASS, TYPE_ID},
                        {"io.github.poisonsheep.wishingfountain.compat.jei.WFRecipeWrapper", TYPE_ID}
                },
                "gui.rs_integration.jei.wishing_fountain_craft");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                TYPE_ID, ModType.byId(TYPE_ID), RSIntegrationConfig.ENABLE_WISHING_FOUNTAIN,
                List.of("io.github.poisonsheep.wishingfountain.block.WFBlock"),
                List.of("wishing_fountain:wishing_fountain"), TYPE_ID, false));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new WishingFountainRecipeHandler());
    }

    @Override public void registerNetworkPackets() {}

    @Override
    public void initCommon() {
        RSIntegrationMod.LOGGER.debug("Wishing Fountain RS module common init done.");
    }
}
