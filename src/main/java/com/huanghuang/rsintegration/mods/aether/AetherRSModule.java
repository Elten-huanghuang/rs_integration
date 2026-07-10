package com.huanghuang.rsintegration.mods.aether;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.AetherRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.function.Supplier;

public final class AetherRSModule implements IModIntegration {

    public static final AetherRSModule INSTANCE = new AetherRSModule();

    private AetherRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_AETHER;
    }

    @Override
    public String modId() {
        return "aether";
    }

    @Override
    public void registerModType() {
        // ── Freezer ──
        ModType.register("aether_freezer",
                new String[]{"com.aetherteam.aether.recipe.recipes.item.Freezable"},
                new String[]{"freezer"},
                new String[]{"aether_freezer"},
                AetherFurnaceBatchDelegate::new);
        ModType.configureJei("aether_freezer",
                new String[][]{{"aether:freezing", "aether_freezer"}},
                new String[][]{{"com.aetherteam.aether.recipe.recipes.item.Freezable", "aether_freezer"}},
                "gui.rs_integration.jei.aether_freezer_craft");

        // ── Incubator ──
        ModType.register("aether_incubator",
                new String[]{"com.aetherteam.aether.recipe.recipes.item.Incubation"},
                new String[]{"incubator"},
                new String[]{"aether_incubator"},
                AetherFurnaceBatchDelegate::new);
        ModType.configureJei("aether_incubator",
                new String[][]{{"aether:incubation", "aether_incubator"}},
                new String[][]{{"com.aetherteam.aether.recipe.recipes.item.Incubation", "aether_incubator"}},
                "gui.rs_integration.jei.aether_incubator_craft");

        // ── Altar (enchanting + repairing) ──
        ModType.register("aether_altar",
                new String[]{"com.aetherteam.aether.recipe.recipes.item.Enchanting",
                        "com.aetherteam.aether.recipe.recipes.item.Repairing"},
                new String[]{"altar"},
                new String[]{"aether_altar"},
                AetherFurnaceBatchDelegate::new);
        ModType.configureJei("aether_altar",
                new String[][]{{"aether:enchanting", "aether_altar"},
                        {"aether:repairing", "aether_altar"}},
                new String[][]{{"com.aetherteam.aether.recipe.recipes.item.Enchanting", "aether_altar"},
                        {"com.aetherteam.aether.recipe.recipes.item.Repairing", "aether_altar"}},
                "gui.rs_integration.jei.aether_altar_craft");

        // ── Generic fallback (other Aether recipe types) ──
        ModType.register("aether",
                new String[]{"com.aetherteam.aether.recipe.recipes.item."},
                new String[]{"freezer", "incubator", "altar"},
                new String[0],
                AetherFurnaceBatchDelegate::new);
        ModType.configureJei("aether",
                new String[][]{{"aether:freezing", "aether_freezer"},
                        {"aether:incubation", "aether_incubator"},
                        {"aether:enchanting", "aether_altar"},
                        {"aether:repairing", "aether_altar"}},
                new String[][]{{"com.aetherteam.aether.", "aether"}},
                null);
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "aether", ModType.byId("aether_freezer"),
                RSIntegrationConfig.ENABLE_AETHER,
                List.of("com.aetherteam.aether.block.utility.FreezerBlock"),
                "aether_freezer"
        ));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "aether", ModType.byId("aether_incubator"),
                RSIntegrationConfig.ENABLE_AETHER,
                List.of("com.aetherteam.aether.block.utility.IncubatorBlock"),
                "aether_incubator"
        ));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "aether", ModType.byId("aether_altar"),
                RSIntegrationConfig.ENABLE_AETHER,
                List.of("com.aetherteam.aether.block.utility.AltarBlock"),
                "aether_altar"
        ));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new AetherRecipeHandler());
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
