package com.huanghuang.rsintegration.mods.slashblade;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.SlashBladeRecipeHandler;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.function.Supplier;

public final class SlashBladeRSModule implements IModIntegration {

    public static final SlashBladeRSModule INSTANCE = new SlashBladeRSModule();

    private SlashBladeRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_SLASHBLADE;
    }

    @Override
    public String modId() {
        return "slashblade";
    }

    @Override
    public void registerModType() {
        ModType.register("slashblade",
                new String[]{"mods.flammpfeil.slashblade.recipe.SlashBladeShapedRecipe"},
                new String[0], new String[0],
                com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate::new);
    }

    @Override
    public void registerBindingTargets() {
        // SlashBlade uses crafting table — no machines to bind
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new SlashBladeRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {
        // No network packets needed — crafting table recipes use standard RS paths
    }

    @Override
    public void initCommon() {}
}
