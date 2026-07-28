package com.huanghuang.rsintegration.mods.lychee;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.common.ForgeConfigSpec;
import java.util.List;

public final class LycheeRSModule implements IModIntegration {

    public static final LycheeRSModule INSTANCE = new LycheeRSModule();
    public static final String TYPE_ID = "lychee_item_inside_virtual";
    public static final String BLOCK_INTERACTING_TYPE_ID = "lychee_block_interacting";

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
        ModType.register(BLOCK_INTERACTING_TYPE_ID,
                new String[]{"snownee.lychee.interaction.BlockInteractingRecipe"},
                new String[]{"hydraulic_press"}, new String[0],
                LycheeBlockInteractingBatchDelegate::new);
        ModType.configureJei(TYPE_ID,
                new String[][]{{"lychee:item_inside/minecraft/default", TYPE_ID}},
                new String[][]{{"snownee.lychee.item_inside.ItemInsideRecipe", TYPE_ID}},
                "gui.rs_integration.jei.lychee_virtual_craft");
        ModType.configureJei(BLOCK_INTERACTING_TYPE_ID,
                new String[][]{{"lychee:block_interacting/minecraft/default", BLOCK_INTERACTING_TYPE_ID}},
                new String[][]{{"snownee.lychee.interaction.BlockInteractingRecipe", BLOCK_INTERACTING_TYPE_ID}},
                "gui.rs_integration.jei.lychee_virtual_craft");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "rustic_engineer", ModType.byId(BLOCK_INTERACTING_TYPE_ID),
                RSIntegrationConfig.ENABLE_LYCHEE, List.of(),
                List.of("rustic_engineer:hydraulic_press"), "hydraulic_press", false));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new LycheeVirtualRecipeHandler());
        ModRecipeHandlers.register(new LycheeBlockInteractingRecipeHandler());
    }

    @Override public void registerNetworkPackets() {}
    @Override public void initCommon() {}
}
