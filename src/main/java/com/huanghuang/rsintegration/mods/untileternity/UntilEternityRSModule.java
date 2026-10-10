package com.huanghuang.rsintegration.mods.untileternity;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class UntilEternityRSModule implements IModIntegration {
    public static final UntilEternityRSModule INSTANCE = new UntilEternityRSModule();
    public static final String TYPE_ID = ModIds.ID_UNTIL_ETERNITY_END_CRAFTING;
    private static final String RECIPE_CLASS =
            "com.carrot123.until_eternity.recipe.EndCraftingRecipe";

    private UntilEternityRSModule() {}

    @Override public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_UNTIL_ETERNITY;
    }

    @Override public String modId() { return ModIds.UNTIL_ETERNITY; }

    @Override
    public void registerModType() {
        ModType.register(TYPE_ID, new String[]{RECIPE_CLASS},
                new String[]{"until_eternity.end_crafting_table"},
                new String[]{TYPE_ID}, EndCraftingBatchDelegate::new);
        ModType.configureJei(TYPE_ID,
                new String[][]{{"until_eternity:end_crafting", TYPE_ID}},
                new String[][]{{RECIPE_CLASS, TYPE_ID}},
                "gui.rs_integration.jei.until_eternity_end_crafting");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.UNTIL_ETERNITY, ModType.byId(TYPE_ID),
                RSIntegrationConfig.ENABLE_UNTIL_ETERNITY,
                List.of("com.carrot123.until_eternity.block.EndCraftingTableBlock"),
                List.of("until_eternity:end_crafting_table"), TYPE_ID, true));
    }

    @Override public void registerRecipeHandler() {
        ModRecipeHandlers.register(new EndCraftingRecipeHandler());
    }
    @Override public void registerNetworkPackets() {}
    @Override public void initCommon() {}
}
