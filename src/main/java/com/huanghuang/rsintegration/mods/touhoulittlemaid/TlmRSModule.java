package com.huanghuang.rsintegration.mods.touhoulittlemaid;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.TlmAltarRecipeHandler;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class TlmRSModule implements IModIntegration {

    public static final TlmRSModule INSTANCE = new TlmRSModule();

    private TlmRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_TOUHOU_LITTLE_MAID;
    }

    @Override
    public String modId() {
        return "touhou_little_maid";
    }

    @Override
    public void registerModType() {
        ModType.register("touhou_little_maid",
                new String[]{"com.github.tartaricacid.touhoulittlemaid."},
                new String[]{"touhou_little_maid"},
                new String[0],
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.touhoulittlemaid.TlmAltarBatchDelegate"));
    }

    @Override
    public void registerBindingTargets() {
        // Maid Altar is in-world interaction (maid stands at altar), no container GUI.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "touhou_little_maid", ModType.byId("touhou_little_maid"),
                RSIntegrationConfig.ENABLE_TOUHOU_LITTLE_MAID, List.of(
                "com.github.tartaricacid.touhoulittlemaid.block.BlockAltar"
        ), "touhou_little_maid", false));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new TlmAltarRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {
        RSIntegrationMod.LOGGER.debug("Touhou Little Maid RS module common init done.");
    }
}
