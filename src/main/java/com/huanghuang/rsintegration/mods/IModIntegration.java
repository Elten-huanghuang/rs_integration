package com.huanghuang.rsintegration.mods;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;

import java.util.function.Supplier;

public interface IModIntegration {

    ForgeConfigSpec.BooleanValue configFlag();

    String modId();

    void registerModType();

    void registerBindingTargets();

    void registerRecipeHandler();

    void registerNetworkPackets();

    void initCommon();

    default Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> {};
    }
}
