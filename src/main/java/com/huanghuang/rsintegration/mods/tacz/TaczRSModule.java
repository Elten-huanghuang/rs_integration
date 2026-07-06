package com.huanghuang.rsintegration.mods.tacz;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.TaczRecipeHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.function.Supplier;

public final class TaczRSModule implements IModIntegration {

    public static final TaczRSModule INSTANCE = new TaczRSModule();

    private TaczRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_TACZ;
    }

    @Override
    public String modId() {
        return "tacz";
    }

    @Override
    public void registerModType() {
        ModType.register("tacz",
                new String[]{"com.tacz.guns.crafting.GunSmithTableRecipe"},
                new String[]{"gun_smith_table_a", "gun_smith_table_b", "gun_smith_table_c"},
                new String[0],
                GenericBatchDelegate::new);
        ModType.configureJei("tacz",
                null,
                new String[][]{{"com.tacz.guns.crafting.GunSmithTableRecipe", "tacz"}},
                "gui.rs_integration.jei.tacz_craft");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "tacz", ModType.byId("tacz"),
                RSIntegrationConfig.ENABLE_TACZ,
                List.of("com.tacz.guns.block.GunSmithTableBlockA",
                        "com.tacz.guns.block.GunSmithTableBlockB",
                        "com.tacz.guns.block.GunSmithTableBlockC"),
                "tacz"
        ));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new TaczRecipeHandler());
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
