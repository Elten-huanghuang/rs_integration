package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.CrockPotRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.function.Supplier;

public final class CrockPotRSModule implements IModIntegration {

    public static final CrockPotRSModule INSTANCE = new CrockPotRSModule();

    private CrockPotRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_CROCKPOT;
    }

    @Override
    public String modId() {
        return "crockpot";
    }

    @Override
    public void registerModType() {
        ModType.register("crockpot",
                new String[]{"com.sihenzhang.crockpot.recipe.cooking.CrockPotCookingRecipe"},
                new String[]{"crock_pot", "portable_crock_pot"},
                new String[0],
                CrockPotBatchDelegate::new);
        ModType.configureJei("crockpot",
                new String[][]{{"crockpot:crock_pot_cooking"}},
                new String[][]{{"com.sihenzhang.crockpot.", "crockpot"}},
                "gui.rs_integration.jei.crockpot_cook");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "crockpot", ModType.byId("crockpot"),
                RSIntegrationConfig.ENABLE_CROCKPOT,
                List.of("com.sihenzhang.crockpot.block.CrockPotBlock"),
                "crockpot"
        ));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new CrockPotRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {}

    @Override
    public void initCommon() {}

    @Override
    public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> {};
    }
}
