package com.huanghuang.rsintegration.mods.confluence;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraftforge.common.ForgeConfigSpec;

public final class ConfluenceRSModule implements IModIntegration {

    public static final ConfluenceRSModule INSTANCE = new ConfluenceRSModule();

    private ConfluenceRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_CONFLUENCE;
    }

    @Override
    public String modId() {
        return "confluence";
    }

    @Override
    public void registerModType() {
        ModType.register("confluence",
                new String[]{"org.confluence.mod.recipe."},
                new String[]{"confluence"},
                new String[0],
                ModType.delegateSupplier("com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate"));
        ModType.configureJei("confluence",
                new String[][]{{"confluence:workshop"}},
                null,
                null);
    }

    @Override
    public void registerBindingTargets() {
        // No BlockEntity — remote GUI opening is registered inline in RSIntegrationMod.
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new WorkshopRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {
        // No custom network packets needed — virtual crafting via GenericBatchDelegate.
    }

    @Override
    public void initCommon() {
        // nothing extra
    }
}
