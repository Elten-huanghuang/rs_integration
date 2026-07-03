package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.EreAlchemyRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class EreAlchemyRSModule implements IModIntegration {

    public static final EreAlchemyRSModule INSTANCE = new EreAlchemyRSModule();

    private EreAlchemyRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY;
    }

    @Override
    public String modId() {
        return "embers";
    }

    @Override
    public void registerModType() {
        ModType.register("embers_alchemy",
                new String[]{"com.rekindled.embers."},
                new String[]{"embers"},
                new String[0],
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.embers.EreAlchemyBatchDelegate"),
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.embers.EreAlchemyInferDelegate"));
        ModType.configureJei("embers_alchemy",
                new String[][]{{"embers:alchemy", "embers"}},
                new String[][]{{"com.rekindled.embers.", "embers"}},
                null);
    }

    @Override
    public void registerBindingTargets() {
        // Alchemy Tablet is in-world interaction (items placed on pedestals), no container GUI.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "embers", ModType.byId("embers_alchemy"),
                RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY, List.of(
                "com.rekindled.embers.block.AlchemyTabletBlock"
        ), "embers_alchemy", false));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new EreAlchemyRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {
        RSIntegrationMod.LOGGER.debug("Embers Alchemy RS module common init done.");
    }
}
