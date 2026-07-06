package com.huanghuang.rsintegration.mods.farmingforblockheads;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.MarketRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;

import java.util.List;

/**
 * Registers the FarmingForBlockheads Market as a bindable machine
 * with virtual batch-crafting support.
 */
public final class FarmingForBlockheadsRSModule {

    public static void initCommon() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                ModIds.FARMINGFORBLOCKHEADS, ModType.FARMINGFORBLOCKHEADS_MARKET,
                RSIntegrationConfig.ENABLE_FARMINGFORBLOCKHEADS,
                List.of("net.blay09.mods.farmingforblockheads.block.MarketBlock"),
                "market"));
        ModRecipeHandlers.register(new MarketRecipeHandler());
    }
}
