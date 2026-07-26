package com.huanghuang.rsintegration.mods.lychee;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;

public final class LycheeRSModule implements IModIntegration {

    public static final LycheeRSModule INSTANCE = new LycheeRSModule();
    public static final String TYPE_ID = "lychee_item_inside_virtual";

    private LycheeRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() { return RSIntegrationConfig.ENABLE_LYCHEE; }

    @Override
    public String modId() { return ModIds.LYCHEE; }

    @Override
    public void registerModType() {
        ModType.registerVirtual(TYPE_ID,
                new String[]{"snownee.lychee.item_inside.ItemInsideRecipe"},
                GenericBatchDelegate::new);
        ModType.configureJei(TYPE_ID,
                new String[][]{{"lychee:item_inside/minecraft/default", TYPE_ID}},
                new String[][]{{"snownee.lychee.item_inside.ItemInsideRecipe", TYPE_ID}},
                "gui.rs_integration.jei.lychee_virtual_craft");
    }

    @Override public void registerBindingTargets() {}

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new LycheeVirtualRecipeHandler());
    }

    @Override public void registerNetworkPackets() {}
    @Override public void initCommon() {}
}
