package com.huanghuang.rsintegration.mods;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.function.Supplier;

public interface IModIntegration {

    ForgeConfigSpec.BooleanValue configFlag();

    String modId();

    /** Mod ids that can provide this integration's runtime API. */
    default List<String> modIds() {
        return List.of(modId());
    }

    void registerModType();

    void registerBindingTargets();

    void registerRecipeHandler();

    void registerNetworkPackets();

    void initCommon();

    default Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> {};
    }
}
